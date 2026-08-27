package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.ui.theme.EditorPalette;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebuggerLineHighlightsTest {
    @Test
    void paintsBreakpointRowsAndLetsExecutionTakeItsOwnRow() {
        RSyntaxTextArea editor = new RSyntaxTextArea("one\ntwo\nthree\nfour");
        DebuggerLineHighlights highlights = new DebuggerLineHighlights(
                editor,
                EditorPalette.islandsDark()
        );

        highlights.setBreakpointLines(java.util.List.of(2, 4));
        highlights.showExecutionLine(4);

        assertEquals(2, highlights.paintedBreakpointCount());
        assertTrue(highlights.isExecutionLinePainted());

        highlights.clearExecutionLine();
        assertEquals(2, highlights.paintedBreakpointCount());
        assertFalse(highlights.isExecutionLinePainted());
        highlights.dispose();
        assertEquals(0, highlights.paintedBreakpointCount());
    }

    @Test
    void selectingAFrameClearsTheExecutionRowFromThePreviousEditor() {
        DebuggerLineHighlights first = highlights("one\ntwo");
        DebuggerLineHighlights second = highlights("one\ntwo");

        DebuggerExecutionLine.show(first, 1);
        DebuggerExecutionLine.show(second, 2);

        assertFalse(first.isExecutionLinePainted());
        assertTrue(second.isExecutionLinePainted());

        DebuggerExecutionLine.clear(second);
        first.dispose();
        second.dispose();
    }

    private static DebuggerLineHighlights highlights(String source) {
        RSyntaxTextArea editor = new RSyntaxTextArea(source);
        return new DebuggerLineHighlights(
                editor,
                EditorPalette.islandsDark()
        );
    }
}
