package com.github.minecraft_ta.totalDebugCompanion.ui.views;

import com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerSessionController;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine;
import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import javax.swing.JTree;
import java.awt.Component;
import java.awt.Container;
import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebuggerPanelTest {
    @Test
    void establishesFrameInspectorSplitOnFirstRealLayout() throws Exception {
        AtomicReference<DebuggerPanel> panelReference = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> panelReference.set(new DebuggerPanel(
                new DebuggerSessionController(),
                (frame, activateEditor) -> {
                }
        )));

        SwingUtilities.invokeAndWait(() -> {
        });

        SwingUtilities.invokeAndWait(() -> {
            DebuggerPanel panel = panelReference.get();
            panel.setSize(1120, 560);
            layoutRecursively(panel);

            JSplitPane split = find(panel, JSplitPane.class);
            assertNotNull(split);
            assertTrue(split.getDividerLocation() >= 400, () ->
                    "Expected the frames pane to occupy about 42% of the window, but divider was at "
                            + split.getDividerLocation());
            panel.dispose();
        });
    }

    @Test
    void hidesDebuggerObjectIdsAndKeepsTheTypeStructured() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DebuggerPanel panel = new DebuggerPanel(
                    new DebuggerSessionController(),
                    (frame, activateEditor) -> {
                    }
            );
            DebugEngine.StackFrame frame = new DebugEngine.StackFrame(
                    1,
                    "performBonemeal",
                    "net.minecraft.world.level.block.GrassBlock",
                    URI.create("file:///GrassBlock.java"),
                    46,
                    1
            );
            panel.showPausedState(new DebuggerSessionController.PausedState(
                    new DebugEngine.StoppedEvent("breakpoint", 1, true),
                    List.of(frame),
                    List.of(new DebugEngine.Variable("level", "ServerLevel@17", "ServerLevel", 5, 4, 0))
            ));

            JTree variables = findVariableTreeOrNull(panel);
            assertNotNull(variables);
            Object value = variables.getPathForRow(0).getLastPathComponent();
            Component rendered = variables.getCellRenderer().getTreeCellRendererComponent(
                    variables,
                    value,
                    false,
                    false,
                    false,
                    0,
                    false
            );
            List<String> labels = labels(rendered);
            assertEquals("level = ServerLevel", labels.getFirst());
            assertFalse(labels.stream().anyMatch(text -> text.contains("@17")), labels.toString());
            panel.dispose();
        });
    }

    private static void layoutRecursively(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container nested) {
                layoutRecursively(nested);
            }
        }
    }

    private static <T extends Component> T find(Container root, Class<T> type) {
        for (Component component : root.getComponents()) {
            if (type.isInstance(component)) {
                return type.cast(component);
            }
            if (component instanceof Container child) {
                T match = find(child, type);
                if (match != null) {
                    return match;
                }
            }
        }
        return null;
    }

    private static JTree findVariableTreeOrNull(Container root) {
        for (Component component : root.getComponents()) {
            if (component instanceof JTree tree && "Variables".equals(tree.getModel().getRoot().toString())) {
                return tree;
            }
            if (component instanceof Container child) {
                JTree match = findVariableTreeOrNull(child);
                if (match != null) {
                    return match;
                }
            }
        }
        return null;
    }

    private static List<String> labels(Component root) {
        java.util.ArrayList<String> labels = new java.util.ArrayList<>();
        if (root instanceof JLabel label) {
            labels.add(label.getText());
        }
        if (root instanceof Container container) {
            for (Component component : container.getComponents()) {
                labels.addAll(labels(component));
            }
        }
        return List.copyOf(labels);
    }
}
