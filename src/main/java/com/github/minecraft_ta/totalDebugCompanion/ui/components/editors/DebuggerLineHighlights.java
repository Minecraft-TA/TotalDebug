package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerSessionController;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.EditorPalette;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;

import javax.swing.text.BadLocationException;
import javax.swing.event.CaretListener;
import java.awt.Color;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Owns breakpoint and selected-frame row backgrounds inside the source editor. */
final class DebuggerLineHighlights {
    private final RSyntaxTextArea editor;
    private final Map<Integer, Object> breakpointHighlights = new LinkedHashMap<>();
    private Set<Integer> breakpointLines = Set.of();
    private Object executionHighlight;
    private int executionLine = -1;
    private final CaretListener executionColorUpdater = event -> updateCurrentLineColor();
    private Color currentLineColor;
    private Color breakpointColor;
    private Color executionColor;

    DebuggerLineHighlights(RSyntaxTextArea editor, EditorPalette palette) {
        this.editor = Objects.requireNonNull(editor, "editor");
        this.editor.addCaretListener(this.executionColorUpdater);
        setPalette(palette);
    }

    void setPalette(EditorPalette palette) {
        Objects.requireNonNull(palette, "palette");
        this.currentLineColor = palette.currentLine();
        this.breakpointColor = palette.breakpointLine();
        this.executionColor = palette.executionLine();
        refreshEditorHighlights();
    }

    void setBreakpointLines(Collection<Integer> displayedLines) {
        Objects.requireNonNull(displayedLines, "displayedLines");
        LinkedHashSet<Integer> normalized = new LinkedHashSet<>();
        for (int line : displayedLines) {
            if (line < 1) {
                throw new IllegalArgumentException("Displayed source line must be positive");
            }
            normalized.add(line);
        }
        this.breakpointLines = Set.copyOf(normalized);
        refreshEditorHighlights();
    }

    void setBreakpoints(Collection<DebuggerSessionController.Breakpoint> breakpoints) {
        Objects.requireNonNull(breakpoints, "breakpoints");
        setBreakpointLines(breakpoints.stream()
                .filter(breakpoint -> breakpoint.state() != DebuggerSessionController.BreakpointState.INVALID)
                .map(DebuggerSessionController.Breakpoint::line)
                .toList());
    }

    void showExecutionLine(int displayedLine) {
        if (displayedLine < 1) {
            throw new IllegalArgumentException("Displayed source line must be positive");
        }
        this.executionLine = displayedLine;
        refreshEditorHighlights();
    }

    void clearExecutionLine() {
        this.executionLine = -1;
        refreshEditorHighlights();
    }

    void sourceChanged() {
        refreshEditorHighlights();
    }

    void dispose() {
        this.editor.removeCaretListener(this.executionColorUpdater);
        removeEditorHighlights();
        this.breakpointLines = Set.of();
        this.executionLine = -1;
        updateCurrentLineColor();
    }

    int paintedBreakpointCount() {
        return this.breakpointHighlights.size();
    }

    boolean isExecutionLinePainted() {
        return this.executionHighlight != null;
    }

    private void refreshEditorHighlights() {
        removeEditorHighlights();
        try {
            for (int line : this.breakpointLines) {
                if (line <= this.editor.getLineCount()) {
                    this.breakpointHighlights.put(
                            line,
                            this.editor.addLineHighlight(line - 1, this.breakpointColor)
                    );
                }
            }
            if (this.executionLine > 0 && this.executionLine <= this.editor.getLineCount()) {
                this.executionHighlight = this.editor.addLineHighlight(
                        this.executionLine - 1,
                        this.executionColor
                );
            }
        } catch (BadLocationException exception) {
            throw new IllegalStateException("Unable to paint debugger source line", exception);
        }
        updateCurrentLineColor();
    }

    private void updateCurrentLineColor() {
        int caretLine = this.editor.getCaretLineNumber() + 1;
        Color color = this.executionLine == caretLine
                ? this.executionColor
                : this.breakpointLines.contains(caretLine) ? this.breakpointColor : this.currentLineColor;
        this.editor.setCurrentLineHighlightColor(color);
    }

    private void removeEditorHighlights() {
        for (Object highlight : this.breakpointHighlights.values()) {
            this.editor.removeLineHighlight(highlight);
        }
        this.breakpointHighlights.clear();
        if (this.executionHighlight != null) {
            this.editor.removeLineHighlight(this.executionHighlight);
            this.executionHighlight = null;
        }
    }
}
