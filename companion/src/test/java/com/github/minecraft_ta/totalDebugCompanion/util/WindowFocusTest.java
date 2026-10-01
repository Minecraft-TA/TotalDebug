package com.github.minecraft_ta.totalDebugCompanion.util;

import org.junit.jupiter.api.Test;

import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import java.awt.Window;
import java.awt.event.WindowEvent;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** What counts as the user coming back to Companion. */
class WindowFocusTest {
    @Test
    void onlyTakingTheFocusBackFromAnotherProgramIsAReturn() throws Exception {
        AtomicInteger returned = new AtomicInteger();
        Runnable stop = WindowFocus.returned().subscribe(returned::incrementAndGet);
        SwingUtilities.invokeAndWait(() -> {
            JWindow main = new JWindow();
            JWindow dialog = new JWindow();
            try {
                // Companion's first window takes the focus as it opens.
                gained(main, null);
                assertEquals(0, returned.get(), "opening is no return");

                // The focus moves between Companion's own windows.
                lost(main, dialog);
                gained(dialog, main);
                assertEquals(0, returned.get(), "a dialog of Companion is no other program");

                // The user goes to the game and comes back.
                lost(dialog, null);
                gained(main, null);
                assertEquals(1, returned.get());
                gained(main, null);
                assertEquals(1, returned.get(), "once per return");
            } finally {
                main.dispose();
                dialog.dispose();
            }
        });
        stop.run();
    }

    private static void gained(Window window, Window from) {
        WindowFocus.changed(new WindowEvent(window, WindowEvent.WINDOW_GAINED_FOCUS, from));
    }

    private static void lost(Window window, Window to) {
        WindowFocus.changed(new WindowEvent(window, WindowEvent.WINDOW_LOST_FOCUS, to));
    }
}
