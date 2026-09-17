package com.github.minecraft_ta.totalDebugCompanion.ui.views.debugger;

import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerSessionController;
import com.github.minecraft_ta.totalDebugCompanion.jdt.diagnostics.ASTCache;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.JavaExpressionField;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.lang.reflect.Field;
import java.net.URI;
import java.awt.event.ActionEvent;
import java.awt.event.WindowEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.KeyboardFocusManager;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BreakpointsWindowTest {
    @Test
    void invalidHitCountKeepsTheEditedRowAndText() throws Exception {
        try (Fixture fixture = new Fixture()) {
            SwingUtilities.invokeAndWait(() -> {
                field(fixture.window, "hitCount", JTextField.class).setText("bad");
                JList<?> list = field(fixture.window, "list", JList.class);
                list.setSelectedIndex(1);
                assertEquals(0, list.getSelectedIndex());
                assertEquals("bad", field(fixture.window, "hitCount", JTextField.class).getText());
            });
        }
    }

    @Test
    void debuggerRefreshPreservesUnfinishedEdits() throws Exception {
        try (Fixture fixture = new Fixture()) {
            SwingUtilities.invokeAndWait(() -> {
                field(fixture.window, "hitCount", JTextField.class).setText("bad");
                fixture.controller.setBreakpointEnabled(fixture.source.uri(), 2, false);
            });
            SwingUtilities.invokeAndWait(() ->
                    assertEquals("bad", field(fixture.window, "hitCount", JTextField.class).getText()));
        }
    }

    @Test
    void validEditsAreAppliedBeforeSwitchingRows() throws Exception {
        try (Fixture fixture = new Fixture()) {
            SwingUtilities.invokeAndWait(() -> {
                field(fixture.window, "hitCount", JTextField.class).setText("5");
                field(fixture.window, "list", JList.class).setSelectedIndex(1);
                assertEquals("5", fixture.controller.breakpoint(fixture.source.uri(), 2).request().hitCondition());
                assertEquals("", field(fixture.window, "hitCount", JTextField.class).getText());
            });
        }
    }

    @Test
    void escapeAndWindowCloseValidateBeforeHiding() throws Exception {
        try (Fixture fixture = new Fixture()) {
            SwingUtilities.invokeAndWait(() -> {
                fixture.window.setVisible(true);
                JTextField hitCount = field(fixture.window, "hitCount", JTextField.class);
                hitCount.setText("bad");
                fixture.window.dispatchEvent(new WindowEvent(fixture.window, WindowEvent.WINDOW_CLOSING));
                assertTrue(fixture.window.isVisible());
                invoke(fixture.window.getRootPane(), JComponent.WHEN_IN_FOCUSED_WINDOW, "ESCAPE");
                assertTrue(fixture.window.isVisible());
                hitCount.setText("7");
                invoke(fixture.window.getRootPane(), JComponent.WHEN_IN_FOCUSED_WINDOW, "ESCAPE");
                assertFalse(fixture.window.isVisible());
                assertEquals("7", fixture.controller.breakpoint(fixture.source.uri(), 2).request().hitCondition());
            });
        }
    }

    @Test
    void keyboardAndMenuShareActionsForTheSelectedBreakpoint() throws Exception {
        try (Fixture fixture = new Fixture()) {
            SwingUtilities.invokeAndWait(() -> {
                JList<?> list = field(fixture.window, "list", JList.class);
                list.setSelectedIndex(1);
                JPopupMenu menu = fixture.window.createContextMenu();
                assertSame(list.getActionMap().get(list.getInputMap().get(KeyStroke.getKeyStroke("ENTER"))),
                        ((JMenuItem) menu.getComponent(0)).getAction());
                assertSame(list.getActionMap().get(list.getInputMap().get(KeyStroke.getKeyStroke("DELETE"))),
                        ((JMenuItem) menu.getComponent(4)).getAction());
                invoke(list, JComponent.WHEN_FOCUSED, "ENTER");
                assertEquals(List.of(new NavigationTarget.RuntimeLine("example.Example", 3)), fixture.navigation);
                invoke(list, JComponent.WHEN_FOCUSED, "SPACE");
                assertEquals(DebuggerSessionController.BreakpointState.DISABLED,
                        fixture.controller.breakpoint(fixture.source.uri(), 3).state());
            });
            SwingUtilities.invokeAndWait(() -> {
                JList<?> list = field(fixture.window, "list", JList.class);
                assertEquals("Enable", ((JMenuItem) fixture.window.createContextMenu().getComponent(1)).getText());
                invoke(list, JComponent.WHEN_FOCUSED, "DELETE");
                assertNull(fixture.controller.breakpoint(fixture.source.uri(), 3));
                assertNotNull(fixture.controller.breakpoint(fixture.source.uri(), 2));
            });
        }
    }

    @Test
    void actionFieldsFollowTheSelectedKindAndRejectEmptyActions() throws Exception {
        try (Fixture fixture = new Fixture()) {
            SwingUtilities.invokeAndWait(() -> {
                JComboBox<?> kind = field(fixture.window, "actionKind", JComboBox.class);
                JPanel sourceField = field(fixture.window, "sourceField", JPanel.class);
                JavaExpressionField source = field(fixture.window, "actionSource", JavaExpressionField.class);
                JList<?> list = field(fixture.window, "list", JList.class);
                assertFalse(sourceField.isVisible());
                kind.setSelectedIndex(1);
                assertTrue(sourceField.isVisible());
                assertTrue(source.isMultiline());
                list.setSelectedIndex(1);
                assertEquals(0, list.getSelectedIndex());
                assertTrue(field(fixture.window, "actionError", JLabel.class).isVisible());
                source.setText("return 1;");
                source.postActionEvent();
                assertEquals("return 1;", fixture.controller.breakpoint(fixture.source.uri(), 2).request().action().source());
                kind.setSelectedIndex(0);
                assertFalse(sourceField.isVisible());
                assertNull(fixture.controller.breakpoint(fixture.source.uri(), 2).request().action());
                source.setText("");
                kind.setSelectedIndex(2);
                assertFalse(source.isMultiline());
                assertEquals("Script:", field(fixture.window, "sourceLabel", JLabel.class).getText());
                source.setText("debug.java");
                source.postActionEvent();
                assertEquals("debug.java", fixture.controller.breakpoint(fixture.source.uri(), 2).request().action().script());
            });
        }
    }

    private static void invoke(JComponent component, int condition, String shortcut) {
        Object key = component.getInputMap(condition).get(KeyStroke.getKeyStroke(shortcut));
        Action action = component.getActionMap().get(key);
        assertNotNull(action, shortcut);
        action.actionPerformed(new ActionEvent(component, ActionEvent.ACTION_PERFORMED, shortcut));
    }

    @Test
    void typingASpaceInSpeedSearchDoesNotToggleTheBreakpoint() throws Exception {
        try (Fixture fixture = new Fixture()) {
            SwingUtilities.invokeAndWait(() -> {
                JList<?> list = field(fixture.window, "list", JList.class);
                var keyboard = KeyboardFocusManager.getCurrentKeyboardFocusManager();
                keyboard.redispatchEvent(list, new KeyEvent(list, KeyEvent.KEY_TYPED,
                        System.currentTimeMillis(), 0, KeyEvent.VK_UNDEFINED, 'E'));
                keyboard.redispatchEvent(list, new KeyEvent(list, KeyEvent.KEY_PRESSED,
                        System.currentTimeMillis(), 0, KeyEvent.VK_SPACE, ' '));
                keyboard.redispatchEvent(list, new KeyEvent(list, KeyEvent.KEY_TYPED,
                        System.currentTimeMillis(), 0, KeyEvent.VK_UNDEFINED, ' '));
                assertNotEquals(DebuggerSessionController.BreakpointState.DISABLED,
                        fixture.controller.breakpoint(fixture.source.uri(), 2).state());
            });
        }
    }

    @Test
    void rightClickCannotOpenAnotherRowsMenuWhenAnEditIsInvalid() throws Exception {
        try (Fixture fixture = new Fixture()) {
            SwingUtilities.invokeAndWait(() -> {
                fixture.window.setVisible(true);
                field(fixture.window, "hitCount", JTextField.class).setText("bad");
                JList<?> list = field(fixture.window, "list", JList.class);
                var bounds = list.getCellBounds(1, 1);
                list.dispatchEvent(new MouseEvent(list, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(),
                        0, 50, bounds.y + bounds.height / 2, 1, true, MouseEvent.BUTTON3));
                assertEquals(0, list.getSelectedIndex());
                assertEquals("bad", field(fixture.window, "hitCount", JTextField.class).getText());
                assertEquals(0, MenuSelectionManager.defaultManager().getSelectedPath().length);
            });
        }
    }

    @Test
    void rightClickTargetsTheClickedRowAndBlankSpaceHasNoMenu() throws Exception {
        try (Fixture fixture = new Fixture()) {
            SwingUtilities.invokeAndWait(() -> {
                fixture.window.setVisible(true);
                JList<?> list = field(fixture.window, "list", JList.class);
                var bounds = list.getCellBounds(1, 1);
                list.dispatchEvent(new MouseEvent(list, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(),
                        0, 50, bounds.y + bounds.height / 2, 1, true, MouseEvent.BUTTON3));
                assertEquals(1, list.getSelectedIndex());
                var menu = (JPopupMenu) MenuSelectionManager.defaultManager().getSelectedPath()[0];
                ((JMenuItem) menu.getComponent(0)).doClick();
                assertEquals(List.of(new NavigationTarget.RuntimeLine("example.Example", 3)), fixture.navigation);
                MenuSelectionManager.defaultManager().clearSelectedPath();
                list.dispatchEvent(new MouseEvent(list, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(),
                        0, 50, list.getHeight() - 5, 1, true, MouseEvent.BUTTON3));
                assertEquals(0, MenuSelectionManager.defaultManager().getSelectedPath().length);
            });
        }
    }

    private static <T> T field(Object instance, String name, Class<T> type) {
        try {
            Field field = instance.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(instance));
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final DebuggerSessionController controller = new DebuggerSessionController(ignored -> null);
        final DebugEngine.Source source = new DebugEngine.Source(
                URI.create("file:///preview/Example.java"), "example.Example", "class Example {}\n\n\n");
        BreakpointsWindow window;
        final List<NavigationTarget> navigation = new ArrayList<>();

        Fixture() throws Exception {
            controller.toggleBreakpoint(source, 2).join();
            controller.toggleBreakpoint(source, 3).join();
            SwingUtilities.invokeAndWait(() -> window = new BreakpointsWindow(new ASTCache(), null, controller, navigation::add));
        }

        @Override
        public void close() throws Exception {
            SwingUtilities.invokeAndWait(window::dispose);
            controller.close();
        }
    }
}
