package com.github.minecraft_ta.totalDebugCompanion.ui.components.inspection;

import com.github.minecraft_ta.totalDebugCompanion.inspection.ItemIconService;
import com.github.minecraft_ta.totalDebugCompanion.ui.CopyValue;
import com.github.minecraft_ta.totaldebug.protocol.execution.Fact;
import com.github.minecraft_ta.totaldebug.protocol.execution.FactSection;
import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FactsPanelTest {
    @Test
    void presentsSlotsBarsAndRowsWithOmittedCounts() throws Exception {
        List<FactSection> sections = List.of(
                new FactSection("Items", List.of(
                        Fact.stack("Slot 0", "minecraft:coal", 8, "Coal"),
                        Fact.stack("Slot 1", "", 0, "")
                ), 30),
                new FactSection("Energy", List.of(Fact.bar("Stored", 5, 10, "FE"), Fact.text("Accepts energy", "Yes")), 2)
        );
        List<String> labels = new ArrayList<>();
        List<String> bars = new ArrayList<>();
        try (ItemIconService icons = new ItemIconService()) {
            SwingUtilities.invokeAndWait(() -> collect(new FactsPanel(sections, icons), labels, bars));
        }

        assertTrue(labels.contains("28 more not shown"), labels::toString);
        assertTrue(labels.contains("Accepts energy"), labels::toString);
        assertEquals(1, bars.size());
        assertTrue(bars.getFirst().endsWith(" FE 50%"), bars::toString);
    }

    @Test
    void theSlotsOfASideFormARowOfTheirOwnMarkedWithWhatTheyDo() throws Exception {
        Fact.Transfer takesAndGives = new Fact.Transfer(true, true);
        List<FactSection> sections = List.of(new FactSection("Items", List.of(
                Fact.stack("Without a side", "minecraft:coal", 8, "Coal").withTransfer(takesAndGives),
                Fact.stack("Without a side", "minecraft:raw_iron", 2, "Raw Iron").withTransfer(takesAndGives),
                Fact.stack("Top", "minecraft:raw_iron", 2, "Raw Iron").withTransfer(takesAndGives),
                Fact.text("Bottom", "Not exposed")), 4));
        List<String> labels = new ArrayList<>();
        try (ItemIconService icons = new ItemIconService()) {
            SwingUtilities.invokeAndWait(() -> collect(new FactsPanel(sections, icons), labels, new ArrayList<>()));
        }

        assertEquals(List.of("Items", "Without a side", "Top", "Bottom", "Not exposed"),
                labels.stream().filter(text -> text != null && !text.isEmpty()).toList());
    }

    @Test
    void aSlotIsMarkedOnlyWithWhatItsStateCanTell() {
        assertEquals("↕", FactsPanel.transferMark(new Fact.Transfer(true, true)));
        assertEquals("↓", FactsPanel.transferMark(new Fact.Transfer(true, false)));
        assertEquals("↑", FactsPanel.transferMark(new Fact.Transfer(null, true)));
        assertEquals("×", FactsPanel.transferMark(new Fact.Transfer(false, false)));
        assertEquals("", FactsPanel.transferMark(new Fact.Transfer(null, false)));
        assertEquals("", FactsPanel.transferMark(new Fact.Transfer(null, null)));
        assertEquals("", FactsPanel.transferMark(null));
    }

    @Test
    void formatsAmountsWithAndWithoutCapacity() {
        Locale previous = Locale.getDefault();
        Locale.setDefault(Locale.US);
        try {
            assertEquals("1,200 / 50,000 FE", FactsPanel.amounts(1_200, 50_000, "FE"));
            assertEquals("7 mB", FactsPanel.amounts(7, 0, "mB"));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void itemModelFollowsTheInventoryConvention() {
        assertEquals("mekanism:item/steel_casing", ItemIconService.itemModel("mekanism:steel_casing"));
        assertEquals("", ItemIconService.itemModel("not-an-id"));
    }

    @Test
    void suggestsAToolNameFromTheRegistryPath() {
        assertEquals("BasicEnergyCubeTool", ToolsMenu.suggestedName("mekanism:basic_energy_cube"));
        assertEquals("Tool2x2DoorTool", ToolsMenu.suggestedName("example:2x2_door"));
    }

    @Test
    void aClippedValueCopiesItsWholeTextAlsoAfterAnUpdate() throws Exception {
        String output = "x".repeat(300);
        String later = "y".repeat(300);
        List<String> copied = new ArrayList<>();
        try (ItemIconService icons = new ItemIconService()) {
            SwingUtilities.invokeAndWait(() -> {
                FactsPanel panel = new FactsPanel(List.of(tool(output)), icons, FactsPanel.Actions.NONE, new HashSet<>());
                copies(panel, copied);
                assertTrue(panel.update(List.of(tool(later))));
                copies(panel, copied);
            });
        }

        assertEquals(List.of(output, output, later, later), copied, "a value shown whole has no copy button");
    }

    private static FactsPanel.Part tool(String text) {
        FactSection section = new FactSection("Tool", List.of(Fact.problem("Status", Fact.clip(text)),
                Fact.text("Output", Fact.clip(text)), Fact.text("Plain", "short")), 3);
        return new FactsPanel.Part(section, null, Map.of("Status", text, "Output", text));
    }

    /** What each row's copy button copies, in order. */
    private static void copies(Container container, List<String> copied) {
        for (Component child : container.getComponents()) {
            if (child instanceof CopyValue copy) copied.add(copy.value());
            if (child instanceof Container nested) copies(nested, copied);
        }
    }

    private static void collect(Container container, List<String> labels, List<String> bars) {
        for (Component child : container.getComponents()) {
            if (child instanceof JLabel label) labels.add(label.getText());
            if (child instanceof FactsPanel.AmountRow bar) bars.add(bar.text());
            if (child instanceof Container nested) collect(nested, labels, bars);
        }
    }
}
