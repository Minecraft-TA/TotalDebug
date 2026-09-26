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
import java.util.Locale;

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
        assertEquals("BasicEnergyCubeTool", ToolsPanel.suggestedName("mekanism:basic_energy_cube"));
        assertEquals("Tool2x2DoorTool", ToolsPanel.suggestedName("example:2x2_door"));
    }

    private static void collect(Container container, List<String> labels, List<String> bars) {
        for (Component child : container.getComponents()) {
            if (child instanceof JLabel label) labels.add(label.getText());
            if (child instanceof FactsPanel.AmountRow bar) bars.add(bar.text());
            if (child instanceof Container nested) collect(nested, labels, bars);
        }
    }
}
