package com.github.minecraft_ta.totalDebugCompanion.ui.views;

import com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerSessionController;
import org.junit.jupiter.api.Test;

import javax.swing.JFrame;
import javax.swing.JComponent;
import javax.swing.JSplitPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.KeyEvent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebuggerWindowTest {

    @Test
    void remainsIndependentFromTheSourceWindowStackingOrder() throws Exception {
        DebuggerSessionController controller = new DebuggerSessionController();
        DebuggerActions actions = new DebuggerActions(controller);
        DebuggerShortcuts shortcuts = new DebuggerShortcuts(actions);
        try {
            SwingUtilities.invokeAndWait(() -> {
                JFrame sourceWindow = new JFrame("Source");
                DebuggerWindow debuggerWindow = new DebuggerWindow(
                        sourceWindow,
                        controller,
                        actions,
                        shortcuts,
                        (frame, activateEditor) -> {
                        }
                );

                try {
                    assertNull(debuggerWindow.getOwner());
                    assertFalse(debuggerWindow.isAlwaysOnTop());
                    KeyStroke f8 = KeyStroke.getKeyStroke(KeyEvent.VK_F8, 0);
                    JSplitPane split = find(debuggerWindow, JSplitPane.class);
                    assertNotNull(split);
                    assertEquals("startResize", split.getInputMap(
                            JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT
                    ).get(f8));
                    assertSame(actions.stepOver(), shortcuts.actionFor(f8));

                    KeyEvent event = new KeyEvent(
                            split,
                            KeyEvent.KEY_PRESSED,
                            System.currentTimeMillis(),
                            0,
                            KeyEvent.VK_F8,
                            KeyEvent.CHAR_UNDEFINED
                    );
                    assertTrue(shortcuts.dispatchKeyEvent(event));
                    assertTrue(event.isConsumed());
                } finally {
                    debuggerWindow.dispose();
                    sourceWindow.dispose();
                }
            });
        } finally {
            shortcuts.close();
            actions.close();
            controller.close();
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
}
