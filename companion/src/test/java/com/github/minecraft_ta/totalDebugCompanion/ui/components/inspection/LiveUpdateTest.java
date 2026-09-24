package com.github.minecraft_ta.totalDebugCompanion.ui.components.inspection;

import com.github.minecraft_ta.totalDebugCompanion.inspection.ItemIconService;
import com.github.minecraft_ta.totaldebug.protocol.execution.Fact;
import com.github.minecraft_ta.totaldebug.protocol.execution.FactSection;
import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveUpdateTest {
    private static List<FactSection> read(long energy, String lit) {
        return List.of(
                new FactSection("Energy", List.of(Fact.bar("Stored", energy, 100, "FE"), Fact.text("Lit", lit)), 2),
                new FactSection("NBT", List.of(Fact.tree("Block entity", "2 entries", List.of(
                        Fact.text("BurnTime", energy + "s"),
                        Fact.tree("Items", "1 item", List.of(Fact.text("[0]", "coal")), 1)), 2)), 1)
        );
    }

    @Test
    void sameShapeUpdatesInPlaceAndMarksOnlyChangedValues() throws Exception {
        try (ItemIconService icons = new ItemIconService()) {
            SwingUtilities.invokeAndWait(() -> {
                FactsPanel panel = new FactsPanel(read(10, "true"), icons);
                AmountBar bar = first(panel, AmountBar.class);
                FactTree tree = first(panel, FactTree.class);
                tree.expandRow(2);
                Set<List<String>> expanded = tree.expandedLabels();

                assertTrue(panel.update(read(25, "true")));

                assertSame(bar, first(panel, AmountBar.class));
                assertEquals("25 / 100 FE", bar.text());
                assertTrue(bar.changed());
                JLabel lit = labels(panel).stream().filter(label -> label.getText().equals("true")).findFirst().orElseThrow();
                assertFalse(lit.isOpaque(), "an unchanged value is not marked");
                assertSame(tree, first(panel, FactTree.class));
                assertEquals(expanded, tree.expandedLabels());

                assertTrue(panel.update(read(25, "false")));
                assertFalse(bar.changed());
            });
        }
    }

    @Test
    void differentShapeRequiresARebuildThatKeepsTreeExpansion() throws Exception {
        try (ItemIconService icons = new ItemIconService()) {
            SwingUtilities.invokeAndWait(() -> {
                FactsPanel panel = new FactsPanel(read(10, "true"), icons);
                FactTree tree = first(panel, FactTree.class);
                tree.expandRow(2);
                List<FactSection> grown = List.of(read(10, "true").getFirst(), new FactSection("NBT", List.of(
                        Fact.tree("Block entity", "3 entries", List.of(
                                Fact.text("BurnTime", "10s"),
                                Fact.tree("Items", "1 item", List.of(Fact.text("[0]", "coal")), 1),
                                Fact.text("CookTime", "3s")), 3)), 1));

                assertFalse(panel.update(grown));
                FactsPanel rebuilt = new FactsPanel(grown, icons, panel.expandedTrees());

                assertEquals(tree.expandedLabels(), first(rebuilt, FactTree.class).expandedLabels());
            });
        }
    }

    private static <T> T first(Container container, Class<T> type) {
        for (Component child : container.getComponents()) {
            if (type.isInstance(child)) return type.cast(child);
            if (child instanceof Container nested) {
                T found = first(nested, type);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static List<JLabel> labels(Container container) {
        List<JLabel> labels = new ArrayList<>();
        for (Component child : container.getComponents()) {
            if (child instanceof JLabel label) labels.add(label);
            if (child instanceof Container nested) labels.addAll(labels(nested));
        }
        return labels;
    }
}
