package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerSessionController;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.EditorPalette;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.junit.jupiter.api.Test;

import java.util.List;

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

    @Test
    void executionColorRemainsVisibleWhenTheCaretIsOnThePausedLine() {
        EditorPalette palette = EditorPalette.islandsDark();
        RSyntaxTextArea editor = new RSyntaxTextArea("paused\nnext");
        editor.setCurrentLineHighlightColor(palette.currentLine());
        DebuggerLineHighlights highlights = new DebuggerLineHighlights(editor, palette);

        highlights.showExecutionLine(1);

        assertEquals(palette.executionLine(), editor.getCurrentLineHighlightColor());

        editor.setCaretPosition(editor.getDocument().getLength());
        assertEquals(palette.currentLine(), editor.getCurrentLineHighlightColor());

        highlights.dispose();
    }

    @Test
    void breakpointColorRemainsVisibleWhenTheCaretMovesOntoItsLine() throws Exception {
        EditorPalette palette = EditorPalette.islandsDark();
        RSyntaxTextArea editor = new RSyntaxTextArea("first\nbreakpoint\nlast");
        editor.setCurrentLineHighlightColor(palette.currentLine());
        DebuggerLineHighlights highlights = new DebuggerLineHighlights(editor, palette);

        editor.setCaretPosition(editor.getLineStartOffset(1));
        highlights.setBreakpointLines(List.of(2));

        assertEquals(palette.breakpointLine(), editor.getCurrentLineHighlightColor());
        highlights.dispose();
    }

    @Test
    void invalidAndDisabledBreakpointsDoNotPaintBreakpointRows() {
        DebuggerLineHighlights highlights = highlights("one\ntwo\nthree");

        highlights.setBreakpoints(List.of(
                breakpoint(2, DebuggerSessionController.BreakpointState.INVALID),
                breakpoint(3, DebuggerSessionController.BreakpointState.DISABLED),
                breakpoint(1, DebuggerSessionController.BreakpointState.BOUND)
        ), false);

        assertEquals(1, highlights.paintedBreakpointCount());
        highlights.dispose();
    }

    private static DebuggerLineHighlights highlights(String source) {
        RSyntaxTextArea editor = new RSyntaxTextArea(source);
        return new DebuggerLineHighlights(
                editor,
                EditorPalette.islandsDark()
        );
    }

    private static DebuggerSessionController.Breakpoint breakpoint(
            int line,
            DebuggerSessionController.BreakpointState state
    ) {
        return new DebuggerSessionController.Breakpoint(
                new DebugEngine.SourceBreakpoint(line),
                state,
                ""
        );
    }
}
