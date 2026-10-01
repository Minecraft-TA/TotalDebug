package com.github.minecraft_ta.totalDebugCompanion.util;

import java.awt.event.WindowEvent;

/**
 * The user coming back to Companion from another program (docs/SYSTEMS.md, section 2). That program, such as the game or
 * an editor, may have written the files Companion shows; nothing watches them, so what reads them reads them again then:
 * the file readings, and the pages that read whenever they are shown. Companion's window tells it of focus changes; a
 * return is a window of Companion taking the focus after Companion lost it, so its first window opening is none.
 */
public final class WindowFocus {
    private static final Signal RETURNED = new Signal();
    /** Whether the focus went to another program. Swing thread only. */
    private static boolean away;

    private WindowFocus() {
    }

    /** Fires on the Swing thread when a window of Companion takes the focus back from another program. */
    public static Signal returned() {
        return RETURNED;
    }

    /** Tells of a window of Companion gaining or losing the focus. Swing thread only. */
    public static void changed(WindowEvent event) {
        // The other window is null where the focus came from, or went to, another program.
        if (event.getOppositeWindow() != null) return;
        if (event.getID() == WindowEvent.WINDOW_LOST_FOCUS) {
            away = true;
        } else if (event.getID() == WindowEvent.WINDOW_GAINED_FOCUS && away) {
            away = false;
            RETURNED.fire();
        }
    }
}
