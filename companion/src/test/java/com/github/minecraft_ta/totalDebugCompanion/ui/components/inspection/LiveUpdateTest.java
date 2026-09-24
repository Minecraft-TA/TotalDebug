package com.github.minecraft_ta.totalDebugCompanion.ui.components.inspection;

import com.github.minecraft_ta.totalDebugCompanion.inspection.ItemIconService;
import com.github.minecraft_ta.totaldebug.protocol.execution.Fact;
import com.github.minecraft_ta.totaldebug.protocol.execution.FactData;
import com.github.minecraft_ta.totaldebug.protocol.execution.FactSection;
import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveUpdateTest {
    /** {@code {BurnTime:<burn>s}} in Minecraft's binary form. */
    private static FactData burnTime(int burn) {
        return FactData.of(new byte[]{10, 2, 0, 8, 'B', 'u', 'r', 'n', 'T', 'i', 'm', 'e', 0, (byte) burn, 0},
                List.of());
    }

    private static List<FactSection> read(long energy, String lit, int burn) {
        return List.of(
                new FactSection("Energy", List.of(Fact.bar("Stored", energy, 100, "FE"), Fact.text("Lit", lit)), 2),
                new FactSection("NBT", List.of(Fact.data("Block entity", burnTime(burn))), 1)
        );
    }

    @Test
    void sameShapeUpdatesInPlaceAndMarksOnlyChangedValues() throws Exception {
        try (ItemIconService icons = new ItemIconService()) {
            SwingUtilities.invokeAndWait(() -> {
                FactsPanel panel = new FactsPanel(read(10, "true", 5), icons);
                FactsPanel.AmountRow bar = first(panel, FactsPanel.AmountRow.class);

                assertTrue(panel.update(read(25, "true", 5)));

                assertSame(bar, first(panel, FactsPanel.AmountRow.class));
                assertEquals("25 / 100 FE 25%", bar.text());
                assertTrue(bar.changed());
                JLabel lit = labels(panel).stream().filter(label -> label.getText().equals("true")).findFirst()
                        .orElseThrow();
                assertFalse(lit.isOpaque(), "an unchanged value is not marked");

                assertTrue(panel.update(read(25, "false", 6)));
                assertFalse(bar.changed());
                JLabel data = labels(panel).stream().filter(label -> label.getText().startsWith("1 key,")).findFirst()
                        .orElseThrow();
                assertTrue(data.isOpaque(), "changed data is marked");
            });
        }
    }

    @Test
    void aSectionThatChangedShapeIsRebuiltAloneAndTheOthersKeepTheirComponents() throws Exception {
        try (ItemIconService icons = new ItemIconService()) {
            SwingUtilities.invokeAndWait(() -> {
                FactsPanel panel = new FactsPanel(read(10, "true", 5), icons);
                FactsPanel.AmountRow bar = first(panel, FactsPanel.AmountRow.class);
                List<FactSection> grown = List.of(read(10, "true", 5).get(0), new FactSection("NBT", List.of(
                        Fact.data("Block entity", burnTime(5)), Fact.data("Second", burnTime(6))), 2));

                assertTrue(panel.update(grown));

                assertSame(bar, first(panel, FactsPanel.AmountRow.class));
                assertTrue(labels(panel).stream().anyMatch(label -> label.getText().equals("Second")));
            });
        }
    }

    @Test
    void dataIsSummarisedByShapeAndSize() {
        assertEquals("1 key, 15 B", FactsPanel.displayedValue(Fact.data("Block entity", burnTime(5))));
        assertEquals("1 key, 15 B, incomplete", FactsPanel.displayedValue(Fact.data("Block entity",
                FactData.of(burnTime(5).bytes(), List.of(new FactData.Omission("", 2))))));
    }

    @Test
    void aDifferentShapeRequiresARebuild() throws Exception {
        try (ItemIconService icons = new ItemIconService()) {
            SwingUtilities.invokeAndWait(() -> {
                FactsPanel panel = new FactsPanel(read(10, "true", 5), icons);
                List<FactSection> grown = new ArrayList<>(read(10, "true", 5));
                grown.add(new FactSection("Fluids", List.of(Fact.fluid("Tank 0", "", 0, 1_000, "")), 1));

                assertFalse(panel.update(grown));
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
