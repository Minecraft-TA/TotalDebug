package com.github.minecraft_ta.totalDebugCompanion.ui.components.inspection;

import com.github.minecraft_ta.totaldebug.protocol.execution.FactData;
import com.github.minecraft_ta.totalDebugCompanion.ui.speedsearch.SpeedSearchTarget;
import org.junit.jupiter.api.Test;

import javax.swing.Action;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataViewTest {
    /** {@code {Items:[{Slot:0b,id:"minecraft:coal"}, ...], id:"minecraft:furnace", note:<note>}} */
    private static FactData furnace(int items, String note, List<FactData.Omission> omissions) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeByte(10);
            output.writeByte(9);
            output.writeUTF("Items");
            output.writeByte(10);
            output.writeInt(items);
            for (int slot = 0; slot < items; slot++) {
                output.writeByte(1);
                output.writeUTF("Slot");
                output.writeByte(slot);
                output.writeByte(8);
                output.writeUTF("id");
                output.writeUTF("minecraft:coal");
                output.writeByte(0);
            }
            output.writeByte(8);
            output.writeUTF("id");
            output.writeUTF("minecraft:furnace");
            output.writeByte(8);
            output.writeUTF("note");
            output.writeUTF(note);
            output.writeByte(0);
        }
        return FactData.of(bytes.toByteArray(), omissions);
    }

    private static List<DataRows.Decoded> decoded(FactData data) {
        return List.of(DataRows.Decoded.of(new DataRows.Root("NBT › Block entity", data)));
    }

    private static List<String> names(List<DataRows.Row> rows) {
        return rows.stream().map(DataRows.Row::name).toList();
    }

    @Test
    void rootsStartExpandedAndNestedEntriesCollapsed() throws IOException {
        List<DataRows.Row> rows = DataRows.visible(decoded(furnace(2, "say \"hi\"", List.of())), Set.of());

        assertEquals(List.of("NBT › Block entity", "Items", "id", "note"), names(rows));
        assertEquals("[{Slot:0b,id:\"minecraft:coal\"},{Slot:1b,id:\"minecraft:coal\"}]", rows.get(1).value());
        assertEquals("list (2)", rows.get(1).type());
        assertEquals("'say \"hi\"'", rows.get(3).value(), "strings print as Minecraft quotes them");
    }

    @Test
    void typingFindsCollapsedEntriesAndRevealsTheSelectedMatch() throws Exception {
        run(() -> {
            DataView view = new DataView();
            view.show(List.of(new DataRows.Root("NBT › Block entity", furnace(2, "", List.of()))));
            SpeedSearchTarget search = view.search();
            int second = -1;
            for (int index = 0; index < search.size(); index++) {
                if (search.textAt(index).equals("Slot 1b")) second = index;
            }

            assertTrue(second >= 0, "collapsed entries are searchable");
            search.select(second);

            assertEquals(List.of("NBT › Block entity", "Items", "[0]", "[1]", "Slot", "id", "id", "note"),
                    names(view.rows()));
            assertEquals("Items[1].Slot", view.rows().get(view.table().getSelectedRow()).pathText());
            assertEquals(second, search.selectedIndex());
        });
    }

    @Test
    void omittedEntriesShowAsRowsAndMakeTheirEnclosingValuesIncomplete() throws IOException {
        FactData data = furnace(1, "", List.of(new FactData.Omission("Items", 40)));
        String items = DataRows.key(new DataRows.Root("NBT › Block entity", data), List.of("Items"));

        List<DataRows.Row> rows = DataRows.visible(decoded(data), Set.of(items));

        assertEquals(List.of("NBT › Block entity", "Items", "[0]", "40 more not transferred", "id", "note"),
                names(rows));
        assertFalse(rows.get(0).complete(), "the root is incomplete");
        assertFalse(rows.get(1).complete());
        assertTrue(rows.get(2).complete(), "the transferred entry is complete");
        assertTrue(rows.get(4).complete());
        assertEquals("1 entry shown, 40 not transferred", rows.get(1).value());
    }

    @Test
    void aNewerReadKeepsExpansionAndSelectionByEntryAndMarksChangedValues() throws Exception {
        run(() -> {
            DataView view = new DataView();
            view.show(List.of(new DataRows.Root("NBT › Block entity", furnace(1, "a", List.of()))));
            view.toggle(1);
            int slot = names(view.rows()).indexOf("[0]");
            view.table().getSelectionModel().setSelectionInterval(slot, slot);

            view.show(List.of(new DataRows.Root("NBT › Block entity", furnace(3, "b", List.of()))));

            assertEquals(List.of("NBT › Block entity", "Items", "[0]", "[1]", "[2]", "id", "note"), names(view.rows()));
            assertEquals("[0]", view.rows().get(view.table().getSelectedRow()).name());
        });
    }

    @Test
    void copyOffersExactSnbtOnlyForCompletelyTransferredEntries() throws Exception {
        run(() -> {
            DataView view = new DataView();
            view.show(List.of(new DataRows.Root("NBT › Block entity",
                    furnace(1, "C:\\path", List.of(new FactData.Omission("Items", 40))))));

            JPopupMenu root = view.menu(0);
            JPopupMenu note = view.menu(names(view.rows()).indexOf("note"));

            assertEquals("Copy value: 40 entries were not transferred", ((JMenuItem) root.getComponent(0)).getText());
            assertFalse(root.getComponent(0).isEnabled());
            assertEquals(List.of("Copy value", "Copy path", "Copy key"), labels(note));
            assertEquals("\"C:\\\\path\"", copied(note, "Copy value"));
            assertEquals("note", copied(note, "Copy path"));
        });
    }

    private static List<String> labels(JPopupMenu menu) {
        List<String> labels = new ArrayList<>();
        for (var component : menu.getComponents()) {
            if (component instanceof JMenuItem item) labels.add(item.getText());
        }
        return labels;
    }

    private static String copied(JPopupMenu menu, String label) {
        for (var component : menu.getComponents()) {
            if (component instanceof JMenuItem item && item.getText().equals(label)) {
                return (String) item.getAction().getValue(Action.ACTION_COMMAND_KEY);
            }
        }
        throw new AssertionError("No " + label);
    }

    private interface Check {
        void run() throws Exception;
    }

    private static void run(Check check) throws Exception {
        Throwable[] failure = new Throwable[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                check.run();
            } catch (Throwable throwable) {
                failure[0] = throwable;
            }
        });
        if (failure[0] instanceof Error error) throw error;
        if (failure[0] instanceof Exception exception) throw exception;
    }
}
