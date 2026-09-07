package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

/** Keeps exactly one selected debugger frame visible across all open editors. */
final class DebuggerExecutionLine {
    private static DebuggerLineHighlights active;

    private DebuggerExecutionLine() {
    }

    static synchronized void show(DebuggerLineHighlights target, int displayedLine) {
        if (active != null && active != target) {
            active.clearExecutionLine();
        }
        active = target;
        target.showExecutionLine(displayedLine);
    }

    static synchronized void clear(DebuggerLineHighlights target) {
        target.clearExecutionLine();
        if (active == target) {
            active = null;
        }
    }
}
