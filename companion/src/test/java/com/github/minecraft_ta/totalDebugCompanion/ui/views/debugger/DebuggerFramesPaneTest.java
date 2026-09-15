package com.github.minecraft_ta.totalDebugCompanion.ui.views.debugger;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseEvent;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DebuggerFramesPaneTest {
    private static final DebugEngine.StackFrame TOP = new DebugEngine.StackFrame(
            1, "Example.run(int)", "example.Example", URI.create("file:///Example.java"), 42, 1);
    private static final DebugEngine.StackFrame CALLER = new DebugEngine.StackFrame(
            2, "Caller.call()", "example.Caller", URI.create("file:///Caller.java"), 10, 1);

    @Test
    void shortcutsAndMenusOpenAndCopyTheSelectedFrameAndWholeStack() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            List<String> copied = new ArrayList<>();
            List<DebugEngine.StackFrame> opened = new ArrayList<>();
            DebuggerFramesPane pane = new DebuggerFramesPane(row -> {}, (frame, activate) -> {
                assertTrue(activate);
                opened.add(frame);
            }, copied::add);
            JList<?> list = list(pane);
            pane.setFrames(List.of(TOP, CALLER));
            list.setSelectedIndex(1);
            JPopupMenu menu = pane.createContextMenu();
            assertSame(Icons.JUMP_TO_SOURCE, ((JMenuItem) menu.getComponent(0)).getIcon());
            assertSame(list.getActionMap().get("Copy frame"), ((JMenuItem) menu.getComponent(2)).getAction());
            assertSame(Icons.COPY, ((JMenuItem) menu.getComponent(3)).getIcon());
            invoke(list, "ENTER");
            invoke(list, "F4");
            assertEquals(List.of(CALLER, CALLER), opened);
            invoke(list, "ctrl C");
            assertEquals("at Caller.call() (example.Caller:10)", copied.getLast());
            invoke(list, "ctrl shift C");
            assertEquals("at Example.run(int) (example.Example:42)" + System.lineSeparator()
                    + "at Caller.call() (example.Caller:10)", copied.getLast());
        });
    }

    @Test
    void missingSourceDisablesNavigationButKeepsCopyingAvailable() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DebuggerFramesPane pane = new DebuggerFramesPane(row -> {}, (frame, activate) -> fail("No source target"));
            JList<?> list = list(pane);
            pane.setFrames(List.of(new DebugEngine.StackFrame(3, "Native.call()", "example.Native", null, -1, 0)));
            pane.selectFirst();
            assertFalse(((JMenuItem) pane.createContextMenu().getComponent(0)).isEnabled());
            assertTrue(((JMenuItem) pane.createContextMenu().getComponent(2)).isEnabled());
            invoke(list, "ENTER");
            pane.setFrames(List.of(new DebugEngine.StackFrame(4, "call", "", URI.create("file:///Unknown.java"), 5, 0)));
            assertFalse(((JMenuItem) pane.createContextMenu().getComponent(0)).isEnabled());
            invoke(list, "F4");
            assertEquals("at call (Unknown.java:5)", DebuggerFramesPane.frameText(pane.frame(0)));
            pane.setFrames(List.of());
            for (var component : pane.createContextMenu().getComponents()) {
                if (component instanceof JMenuItem item) assertFalse(item.isEnabled());
            }
            invoke(list, "ENTER");
            invoke(list, "ctrl C");
            invoke(list, "ctrl shift C");
        });
    }

    @Test
    void rightClickTargetsItsRowAndRefreshingFramesDismissesTheMenu() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            List<Integer> selected = new ArrayList<>();
            List<DebugEngine.StackFrame> opened = new ArrayList<>();
            DebuggerFramesPane pane = new DebuggerFramesPane(selected::add, (frame, activate) -> opened.add(frame));
            JFrame window = new JFrame();
            try {
                window.setContentPane(pane);
                window.setSize(500, 300);
                window.setVisible(true);
                pane.setFrames(List.of(TOP, CALLER));
                pane.selectFirst();
                JList<?> list = list(pane);
                var bounds = list.getCellBounds(1, 1);
                list.dispatchEvent(new MouseEvent(list, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(),
                        0, 50, bounds.y + bounds.height / 2, 1, true, MouseEvent.BUTTON3));
                assertEquals(1, list.getSelectedIndex());
                assertEquals(1, selected.getLast());
                var menu = (JPopupMenu) MenuSelectionManager.defaultManager().getSelectedPath()[0];
                ((JMenuItem) menu.getComponent(0)).doClick();
                assertEquals(List.of(CALLER), opened);
                menu.show(list, 0, 0);
                pane.setFrames(List.of(TOP));
                assertFalse(menu.isVisible());
                assertEquals(0, MenuSelectionManager.defaultManager().getSelectedPath().length);
            } finally {
                window.dispose();
            }
        });
    }

    @Test
    void clickingBlankSpaceDoesNotOpenTheLastSelectedFrameOrAMenu() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DebuggerFramesPane pane = new DebuggerFramesPane(row -> {}, (frame, activate) -> fail("Blank space"));
            pane.setFrames(List.of(TOP));
            pane.selectFirst();
            JList<?> list = list(pane);
            list.setSize(400, 300);
            list.dispatchEvent(new MouseEvent(list, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(),
                    0, 30, 250, 2, false, MouseEvent.BUTTON1));
            list.dispatchEvent(new MouseEvent(list, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(),
                    0, 30, 250, 1, true, MouseEvent.BUTTON3));
            assertEquals(0, MenuSelectionManager.defaultManager().getSelectedPath().length);
        });
    }

    private static JList<?> list(DebuggerFramesPane pane) {
        for (var component : pane.getComponents()) {
            if (component instanceof JScrollPane scroll) return (JList<?>) scroll.getViewport().getView();
        }
        throw new AssertionError("Missing frame list");
    }

    private static void invoke(JComponent component, String shortcut) {
        Object key = component.getInputMap(JComponent.WHEN_FOCUSED).get(KeyStroke.getKeyStroke(shortcut));
        Action action = component.getActionMap().get(key);
        assertNotNull(action, shortcut);
        action.actionPerformed(new ActionEvent(component, ActionEvent.ACTION_PERFORMED, shortcut));
    }
}
