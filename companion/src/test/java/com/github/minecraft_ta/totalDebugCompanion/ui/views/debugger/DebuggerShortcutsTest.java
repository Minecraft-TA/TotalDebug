package com.github.minecraft_ta.totalDebugCompanion.ui.views.debugger;

import com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerSessionController;
import org.junit.jupiter.api.Test;

import javax.swing.JFrame;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebuggerShortcutsTest {
    @Test
    void sharesDebuggerCommandsAcrossRegisteredCompanionWindows() throws Exception {
        DebuggerSessionController controller = new DebuggerSessionController(ignored -> null);
        DebuggerActions actions = new DebuggerActions(controller);
        DebuggerShortcuts shortcuts = new DebuggerShortcuts(actions);
        try {
            SwingUtilities.invokeAndWait(() -> {
                JFrame editorWindow = new JFrame();
                JFrame debuggerWindow = new JFrame();
                JFrame unrelatedWindow = new JFrame();
                try {
                    shortcuts.install(editorWindow);
                    shortcuts.install(debuggerWindow);

                    assertSame(actions.resume(), shortcuts.actionFor(
                            KeyStroke.getKeyStroke(KeyEvent.VK_F9, 0)
                    ));
                    assertSame(actions.stepOver(), shortcuts.actionFor(
                            KeyStroke.getKeyStroke(KeyEvent.VK_F8, 0)
                    ));
                    assertSame(actions.stepInto(), shortcuts.actionFor(
                            KeyStroke.getKeyStroke(KeyEvent.VK_F7, 0)
                    ));
                    assertSame(actions.stepOut(), shortcuts.actionFor(
                            KeyStroke.getKeyStroke(KeyEvent.VK_F8, InputEvent.SHIFT_DOWN_MASK)
                    ));

                    actions.applyStatus(status(DebuggerSessionController.Phase.PAUSED));
                    assertTrue(actions.resume().isEnabled());
                    assertTrue(actions.stepOver().isEnabled());
                    assertTrue(actions.stepInto().isEnabled());
                    assertTrue(actions.stepOut().isEnabled());

                    actions.applyStatus(status(DebuggerSessionController.Phase.RUNNING));
                    assertFalse(actions.resume().isEnabled());
                    assertFalse(actions.stepOver().isEnabled());
                    assertFalse(actions.stepInto().isEnabled());
                    assertFalse(actions.stepOut().isEnabled());

                    assertTrue(shortcuts.dispatchKeyEvent(f8(editorWindow)));
                    assertTrue(shortcuts.dispatchKeyEvent(f8(debuggerWindow)));
                    assertFalse(shortcuts.dispatchKeyEvent(f8(unrelatedWindow)));
                } finally {
                    editorWindow.dispose();
                    debuggerWindow.dispose();
                    unrelatedWindow.dispose();
                }
            });
        } finally {
            shortcuts.close();
            actions.close();
            controller.close();
        }
    }

    private static KeyEvent f8(JFrame source) {
        return new KeyEvent(
                source,
                KeyEvent.KEY_PRESSED,
                System.currentTimeMillis(),
                0,
                KeyEvent.VK_F8,
                KeyEvent.CHAR_UNDEFINED
        );
    }

    private static DebuggerSessionController.Status status(DebuggerSessionController.Phase phase) {
        return new DebuggerSessionController.Status(phase, null, phase.name(), null);
    }
}
