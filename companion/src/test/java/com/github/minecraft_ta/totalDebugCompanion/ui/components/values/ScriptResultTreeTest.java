package com.github.minecraft_ta.totalDebugCompanion.ui.components.values;

import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionText;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionValue;
import org.junit.jupiter.api.Test;
import java.util.List;
import javax.swing.JMenuItem;
import javax.swing.SwingUtilities;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptResultTreeTest {
    @Test
    void copyMenuKeepsTheCapturedValueBeyondTheRendererLimitAndDoesNotInventAnExpression() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            String value = "x".repeat(800);
            ScriptResultTree tree = new ScriptResultTree();
            tree.showResult(new ExecutionValue(text("java.lang.String"), text(value), text(""),
                    ExecutionValue.Kind.STRING, 0, 0, false, List.of()));
            var menu = tree.createContextMenu(tree.getSelectionPath());
            assertEquals(2, menu.getComponentCount());
            var copyValue = (JMenuItem) menu.getComponent(0);
            var copyType = (JMenuItem) menu.getComponent(1);
            assertEquals("Copy Value", copyValue.getText());
            assertEquals('"' + value + '"', copyValue.getActionCommand());
            assertEquals("Copy Type", copyType.getText());
            assertEquals("java.lang.String", copyType.getActionCommand());
            tree.clearResult();
            assertEquals(0, tree.createContextMenu(tree.getSelectionPath()).getComponentCount());
        });
    }

    @Test
    void boundsSearchTextWithoutChangingTheCanonicalValue() {
        String value = "x".repeat(1_000_000);
        ExecutionValue snapshot = new ExecutionValue(
                text("java.lang.String"),
                text(value),
                text(""),
                ExecutionValue.Kind.STRING,
                0,
                0,
                false,
                List.of()
        );

        String searchText = ScriptResultTree.boundedSearchText("result", snapshot);

        assertEquals(2_048, searchText.length());
        assertTrue(searchText.startsWith("result java.lang.String "));
        assertEquals(value, snapshot.value().text());
    }

    private static ExecutionText text(String value) {
        return new ExecutionText(value, value.length(), false);
    }
}
