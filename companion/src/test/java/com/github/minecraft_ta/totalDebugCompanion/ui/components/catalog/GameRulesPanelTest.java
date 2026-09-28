package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import org.junit.jupiter.api.Test;

import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameRulesPanelTest {
    @Test
    void aValueIsSetAtOnceAndPutBackWhenTheGameRefusesIt() throws Exception {
        List<String> set = new ArrayList<>();
        CompletableFuture<Object> answer = new CompletableFuture<>();
        GameRulesPanel[] panel = new GameRulesPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            panel[0] = new GameRulesPanel();
            panel[0].setSetter((name, value) -> {
                set.add(name + "=" + value);
                return answer;
            });
            panel[0].setRules(new TreeMap<>(Map.of("keepInventory", "true", "randomTickSpeed", "3")));

            assertTrue(panel[0].table().editCellAt(1, 1));
            ((JTextField) panel[0].table().getEditorComponent()).setText("fast");
            assertFalse(panel[0].table().getCellEditor().stopCellEditing(), "a refused value stays in the editor");
            assertTrue(panel[0].table().isEditing());
            assertEquals("Not set: randomTickSpeed: Enter a whole number", panel[0].notice());
            panel[0].table().getCellEditor().cancelCellEditing();

            panel[0].edit(1, "fast");
            assertEquals("Not set: randomTickSpeed: Enter a whole number", panel[0].notice());
            assertTrue(set.isEmpty(), "refused before the game is asked");

            panel[0].edit(1, "10");
            assertEquals(List.of("keepInventory=true", "randomTickSpeed=10"), panel[0].shownValues(), "shown at once");
            assertEquals("", panel[0].notice());
        });
        // The game names its rules while the set is pending: a command set the rule meanwhile.
        SwingUtilities.invokeAndWait(() -> panel[0].setRules(new TreeMap<>(Map.of("keepInventory", "true", "randomTickSpeed", "7"))));
        answer.completeExceptionally(new IllegalStateException("The world is closed"));
        SwingUtilities.invokeAndWait(() -> { });
        SwingUtilities.invokeAndWait(() -> {
            assertEquals(List.of("randomTickSpeed=10"), set);
            assertEquals(List.of("keepInventory=true", "randomTickSpeed=7"), panel[0].shownValues(),
                    "put back to what the game named last");
            assertEquals("Not set: randomTickSpeed: The world is closed", panel[0].notice());
        });
    }
}
