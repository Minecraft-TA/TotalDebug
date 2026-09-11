package com.github.minecraft_ta.totalDebugCompanion.ui.views.debugger;

import com.github.minecraft_ta.totalDebugCompanion.storage.InstanceState;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerSessionController;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.JavaExpressionField;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.editors.DebuggerEditorPresentation;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import javax.swing.JTree;
import javax.swing.JList;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebuggerPanelTest {
    @Test
    void exposesExplicitEvaluateAndAddWatchActions() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DebuggerSessionController controller = new DebuggerSessionController(ignored -> null);
            DebuggerActions actions = new DebuggerActions(controller);
            DebuggerPanel panel = new DebuggerPanel(InstanceState.inMemory(), controller, actions, (frame, activateEditor) -> {
            });

            JavaExpressionField field = find(panel, JavaExpressionField.class);
            assertNotNull(field);
            assertEquals(
                    "Evaluate Expression (Enter); Add Watch (Shift+Enter)",
                    field.getToolTipText()
            );

            JButton addWatch = findButtonByTooltip(panel, "Add Watch (Shift+Enter)");
            assertNotNull(addWatch);
            assertTrue(addWatch.getText() == null || addWatch.getText().isBlank());
            assertEquals("Add Watch (Shift+Enter)", addWatch.getToolTipText());

            Object actionKey = field.getInputMap(JComponent.WHEN_FOCUSED).get(
                    javax.swing.KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK)
            );
            assertNotNull(actionKey);
            assertNotNull(field.getActionMap().get(actionKey));

            panel.dispose();
            actions.close();
            controller.close();
        });
    }

    @Test
    void establishesFrameInspectorSplitOnFirstRealLayout() throws Exception {
        AtomicReference<DebuggerPanel> panelReference = new AtomicReference<>();
        DebuggerSessionController controller = new DebuggerSessionController(ignored -> null);
        DebuggerActions actions = new DebuggerActions(controller);
        SwingUtilities.invokeAndWait(() -> panelReference.set(new DebuggerPanel(InstanceState.inMemory(),
                controller,
                actions,
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
        actions.close();
        controller.close();
    }

    @Test
    void hidesDebuggerObjectIdsAndKeepsTheTypeStructured() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DebuggerSessionController controller = new DebuggerSessionController(ignored -> null);
            DebuggerActions actions = new DebuggerActions(controller);
            DebuggerPanel panel = new DebuggerPanel(InstanceState.inMemory(),
                    controller,
                    actions,
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
                    List.of(new DebugEngine.Variable(
                            "level", "level", "level", "ServerLevel@17",
                            "net.minecraft.server.level.ServerLevel", DebugEngine.VariableKind.PARAMETER,
                            1, 5, 4, 0
                    ))
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
            actions.close();
            controller.close();
        });
    }

    @Test
    void focusesTheDebuggerVariableRequestedByAnEditorHint() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DebuggerSessionController controller = new DebuggerSessionController(ignored -> null);
            DebuggerActions actions = new DebuggerActions(controller);
            DebuggerPanel panel = new DebuggerPanel(InstanceState.inMemory(), controller, actions, (frame, activateEditor) -> {
            });
            DebugEngine.StackFrame frame = new DebugEngine.StackFrame(
                    1,
                    "performBonemeal",
                    "net.minecraft.world.level.block.GrassBlock",
                    URI.create("file:///GrassBlock.java"),
                    46,
                    1
            );
            DebugEngine.Variable random = new DebugEngine.Variable(
                    "random",
                    "random",
                    "random",
                    "net.minecraft.world.level.levelgen.LegacyRandomSource@45",
                    "net.minecraft.util.RandomSource",
                    DebugEngine.VariableKind.PARAMETER,
                    1,
                    5,
                    4,
                    0
            );
            panel.showPausedState(new DebuggerSessionController.PausedState(
                    new DebugEngine.StoppedEvent("breakpoint", 1, true),
                    List.of(frame),
                    List.of(random)
            ));

            panel.focusVariable(frame, random);

            JTree variables = findVariableTreeOrNull(panel);
            assertNotNull(variables);
            assertNotNull(variables.getSelectionPath());
            assertEquals(0, variables.getSelectionRows()[0]);
            panel.dispose();
            actions.close();
            controller.close();
        });
    }

    @Test
    void displaysThisBeforeParametersAndLocals() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DebuggerSessionController controller = new DebuggerSessionController(ignored -> null);
            DebuggerActions actions = new DebuggerActions(controller);
            DebuggerPanel panel = new DebuggerPanel(InstanceState.inMemory(), controller, actions, (frame, activateEditor) -> {
            });
            DebugEngine.StackFrame frame = new DebugEngine.StackFrame(
                    1,
                    "Target.run",
                    "example.Target",
                    URI.create("decompiled:///example/Target.java"),
                    20,
                    1
            );
            panel.showPausedState(new DebuggerSessionController.PausedState(
                    new DebugEngine.StoppedEvent("breakpoint", 1, true),
                    List.of(frame),
                    List.of(
                            new DebugEngine.Variable(
                                    "input", "input", "input", "7", "int",
                                    DebugEngine.VariableKind.PARAMETER, 1, 0, 0, 0
                            ),
                            new DebugEngine.Variable(
                                    "this", "this", "this", "Target@1", "example.Target",
                                    DebugEngine.VariableKind.THIS, 1, 2, 2, 0
                            )
                    )
            ));

            JTree variables = findVariableTreeOrNull(panel);
            assertNotNull(variables);
            Component rendered = variables.getCellRenderer().getTreeCellRendererComponent(
                    variables,
                    variables.getPathForRow(0).getLastPathComponent(),
                    false,
                    false,
                    false,
                    0,
                    false
            );
            assertTrue(labels(rendered).getFirst().startsWith("this = "));
            panel.dispose();
            actions.close();
            controller.close();
        });
    }

    @Test
    void offersNavigationAssignmentAndCopyActionsForAddressableVariables() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DebuggerSessionController controller = new DebuggerSessionController(ignored -> null);
            DebuggerActions actions = new DebuggerActions(controller);
            DebuggerPanel panel = new DebuggerPanel(InstanceState.inMemory(),
                    controller,
                    actions,
                    (frame, activateEditor) -> {
                    },
                    () -> {
                    },
                    target -> {
                    }
            );
            DebugEngine.StackFrame frame = new DebugEngine.StackFrame(
                    1,
                    "Target.run",
                    "example.Target",
                    URI.create("decompiled:///example/Target.java"),
                    20,
                    1
            );
            panel.showPausedState(new DebuggerSessionController.PausedState(
                    new DebugEngine.StoppedEvent("breakpoint", 1, true),
                    List.of(frame),
                    List.of(new DebugEngine.Variable(
                            "input", "p_12345_", "input", "Target@1", "example.Target",
                            DebugEngine.VariableKind.PARAMETER, 9, 2, 2, 0
                    ))
            ));

            JTree variables = findVariableTreeOrNull(panel);
            assertNotNull(variables);
            DebuggerInspector inspector = find(panel, DebuggerInspector.class);
            assertNotNull(inspector);
            JPopupMenu menu = inspector.createContextMenu(variables.getPathForRow(0));
            assertEquals(
                    List.of(
                            "Jump to Source",
                            "Jump to Type Source",
                            "Set Value…",
                            "Copy Value",
                            "Copy Expression"
                    ),
                    menuItems(menu)
            );

            panel.dispose();
            actions.close();
            controller.close();
        });
    }

    @Test
    void selectingAStackFrameNavigatesItsExactRuntimeClassAndLine() throws Exception {
        AtomicReference<DebugEngine.StackFrame> navigated = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            DebuggerSessionController controller = new DebuggerSessionController(ignored -> null);
            DebuggerActions actions = new DebuggerActions(controller);
            DebuggerPanel panel = new DebuggerPanel(InstanceState.inMemory(),
                    controller,
                    actions,
                    (frame, activateEditor) -> navigated.set(frame)
            );
            DebugEngine.StackFrame implementation = new DebugEngine.StackFrame(
                    1,
                    "ConcreteBlock.update",
                    "example.ConcreteBlock",
                    URI.create("decompiled:///example/ConcreteBlock.java"),
                    47,
                    1
            );
            DebugEngine.StackFrame caller = new DebugEngine.StackFrame(
                    2,
                    "Dispatcher.tick",
                    "example.Dispatcher",
                    URI.create("decompiled:///example/Dispatcher.java"),
                    92,
                    1
            );
            panel.showPausedState(new DebuggerSessionController.PausedState(
                    new DebugEngine.StoppedEvent("breakpoint", 1, true),
                    List.of(implementation, caller),
                    List.of()
            ));

            assertEquals(implementation, navigated.get());
            @SuppressWarnings("rawtypes")
            JList frames = find(panel, JList.class);
            assertNotNull(frames);
            frames.setSelectedIndex(1);
            assertEquals(caller, navigated.get());
            panel.dispose();
            actions.close();
            controller.close();
        });
    }

    @Test
    void keepsThePreviousPauseVisibleUntilTheNextPauseReplacesItAtomically() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DebuggerSessionController controller = new DebuggerSessionController(ignored -> null);
            DebuggerActions actions = new DebuggerActions(controller);
            AtomicReference<DebugEngine.StackFrame> navigated = new AtomicReference<>();
            DebuggerPanel panel = new DebuggerPanel(InstanceState.inMemory(),
                    controller,
                    actions,
                    (frame, activateEditor) -> navigated.set(frame)
            );
            DebugEngine.StackFrame first = new DebugEngine.StackFrame(
                    1,
                    "Target.first",
                    "example.Target",
                    URI.create("decompiled:///example/Target.java"),
                    20,
                    1
            );
            DebugEngine.StackFrame second = new DebugEngine.StackFrame(
                    2,
                    "Target.second",
                    "example.Target",
                    URI.create("decompiled:///example/Target.java"),
                    24,
                    1
            );
            panel.showPausedState(paused(first));

            @SuppressWarnings("rawtypes")
            JList frames = find(panel, JList.class);
            assertNotNull(frames);
            assertEquals(first, frames.getModel().getElementAt(0));

            panel.applyStatus(new DebuggerSessionController.Status(
                    DebuggerSessionController.Phase.RUNNING,
                    null,
                    "Running",
                    null
            ));
            assertEquals(1, frames.getModel().getSize());
            assertEquals(first, frames.getModel().getElementAt(0));

            AtomicBoolean observedEmptyModel = new AtomicBoolean();
            frames.getModel().addListDataListener(new ListDataListener() {
                @Override
                public void intervalAdded(ListDataEvent event) {
                    observe();
                }

                @Override
                public void intervalRemoved(ListDataEvent event) {
                    observe();
                }

                @Override
                public void contentsChanged(ListDataEvent event) {
                    observe();
                }

                private void observe() {
                    observedEmptyModel.compareAndSet(false, frames.getModel().getSize() == 0);
                }
            });

            panel.showPausedState(paused(second));
            assertFalse(observedEmptyModel.get(), "Replacing a pause published an empty frame model");
            assertEquals(second, frames.getModel().getElementAt(0));
            assertEquals(second, navigated.get());

            panel.applyStatus(new DebuggerSessionController.Status(
                    DebuggerSessionController.Phase.DETACHED,
                    null,
                    "Detached",
                    null
            ));
            assertEquals(0, frames.getModel().getSize());
            panel.dispose();
            actions.close();
            controller.close();
        });
    }

    @Test
    void clearsEditorValuesWhileRetainingTheDebuggerSnapshotOnResume() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DebuggerSessionController controller = new DebuggerSessionController(ignored -> null);
            DebuggerActions actions = new DebuggerActions(controller);
            DebuggerPanel panel = new DebuggerPanel(InstanceState.inMemory(), controller, actions, (frame, activateEditor) -> {
            });
            DebugEngine.StackFrame frame = new DebugEngine.StackFrame(
                    1,
                    "Target.run",
                    "example.Target",
                    URI.create("decompiled:///example/Target.java"),
                    20,
                    1
            );
            AtomicReference<DebuggerEditorPresentation.Snapshot> editorSnapshot = new AtomicReference<>();
            Runnable removeListener = DebuggerEditorPresentation.addListener(editorSnapshot::set);
            panel.showPausedState(paused(frame));
            assertNotNull(editorSnapshot.get());

            panel.applyStatus(new DebuggerSessionController.Status(
                    DebuggerSessionController.Phase.RUNNING,
                    null,
                    "Running",
                    null
            ));

            assertNull(editorSnapshot.get(), "Editor values remained visible after execution resumed");
            @SuppressWarnings("rawtypes")
            JList frames = find(panel, JList.class);
            assertEquals(1, frames.getModel().getSize(), "Debugger snapshot should remain stable while stepping");
            removeListener.run();
            panel.dispose();
            actions.close();
            controller.close();
        });
    }

    private static DebuggerSessionController.PausedState paused(DebugEngine.StackFrame frame) {
        return new DebuggerSessionController.PausedState(
                new DebugEngine.StoppedEvent("step", 1, true),
                List.of(frame),
                List.of()
        );
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

    private static JButton findButtonByTooltip(Container root, String tooltip) {
        for (Component component : root.getComponents()) {
            if (component instanceof JButton button && tooltip.equals(button.getToolTipText())) {
                return button;
            }
            if (component instanceof Container child) {
                JButton match = findButtonByTooltip(child, tooltip);
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

    private static List<String> menuItems(JPopupMenu menu) {
        java.util.ArrayList<String> items = new java.util.ArrayList<>();
        for (Component component : menu.getComponents()) {
            if (component instanceof JMenuItem item) {
                items.add(item.getText());
            }
        }
        return List.copyOf(items);
    }
}
