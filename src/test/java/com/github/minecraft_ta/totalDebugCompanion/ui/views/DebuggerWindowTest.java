package com.github.minecraft_ta.totalDebugCompanion.ui.views;

import com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerSessionController;
import org.junit.jupiter.api.Test;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class DebuggerWindowTest {

    @Test
    void remainsIndependentFromTheSourceWindowStackingOrder() throws Exception {
        DebuggerSessionController controller = new DebuggerSessionController();
        try {
            SwingUtilities.invokeAndWait(() -> {
                JFrame sourceWindow = new JFrame("Source");
                DebuggerWindow debuggerWindow = new DebuggerWindow(
                        sourceWindow,
                        controller,
                        (frame, activateEditor) -> {
                        }
                );

                try {
                    assertNull(debuggerWindow.getOwner());
                    assertFalse(debuggerWindow.isAlwaysOnTop());
                } finally {
                    debuggerWindow.dispose();
                    sourceWindow.dispose();
                }
            });
        } finally {
            controller.close();
        }
    }
}
