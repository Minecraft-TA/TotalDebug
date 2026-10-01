package com.github.minecraft_ta.totalDebugCompanion.util;

/**
 * The user coming back to Companion from another program (docs/SYSTEMS.md, section 2). That program, such as the game or
 * an editor, may have written the files Companion shows; nothing watches them, so what reads them reads them again then:
 * the file readings, and the pages that read whenever they are shown. Companion's window fires it.
 */
public final class WindowFocus {
    private static final Signal RETURNED = new Signal();

    private WindowFocus() {
    }

    /** Fires on the Swing thread when a window of Companion gains the focus from another program. */
    public static Signal returned() {
        return RETURNED;
    }
}
