package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rtextarea.Gutter;
import org.fife.ui.rtextarea.GutterIconInfo;
import org.fife.ui.rtextarea.LineNumberList;

import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import java.awt.Component;
import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.IntConsumer;

final class BreakpointGutterMarkers {
    private final Gutter gutter;
    private final RSyntaxTextArea editor;
    private final IntConsumer toggleBreakpoint;
    private final LineNumberList lineNumbers;
    private final List<GutterIconInfo> installed = new ArrayList<>();
    private final MouseAdapter lineNumberClicks = new MouseAdapter() {
        @Override
        public void mouseClicked(MouseEvent event) {
            if (!SwingUtilities.isLeftMouseButton(event) || event.getClickCount() != 1) {
                return;
            }
            int displayedLine = displayedLineAt(event.getY());
            if (displayedLine > 0) {
                toggleBreakpoint.accept(displayedLine);
            }
        }
    };

    BreakpointGutterMarkers(Gutter gutter, RSyntaxTextArea editor, IntConsumer toggleBreakpoint) {
        this.gutter = Objects.requireNonNull(gutter, "gutter");
        this.editor = Objects.requireNonNull(editor, "editor");
        this.toggleBreakpoint = Objects.requireNonNull(toggleBreakpoint, "toggleBreakpoint");
        this.lineNumbers = findLineNumbers(this.gutter);
        this.lineNumbers.addMouseListener(this.lineNumberClicks);
    }

    void setBreakpoints(List<com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine.SourceBreakpoint> breakpoints) {
        clear();
        for (var breakpoint : breakpoints) {
            try {
                boolean dependent = breakpoint.condition() != null && !breakpoint.condition().isBlank()
                        || breakpoint.hitCondition() != null && !breakpoint.hitCondition().isBlank();
                this.installed.add(this.gutter.addLineTrackingIcon(
                        breakpoint.line() - 1,
                        dependent ? Icons.BREAKPOINT_DEPENDENT : Icons.BREAKPOINT,
                        dependent
                                ? "Conditional breakpoint at line " + breakpoint.line()
                                : "Breakpoint at line " + breakpoint.line()
                ));
            } catch (javax.swing.text.BadLocationException ignored) {
            }
        }
    }

    void dispose() {
        this.lineNumbers.removeMouseListener(this.lineNumberClicks);
        clear();
    }

    private int displayedLineAt(int y) {
        int offset = this.editor.viewToModel2D(new Point(0, y));
        if (offset < 0) {
            return -1;
        }
        try {
            int line = this.editor.getLineOfOffset(offset);
            var bounds = this.editor.modelToView2D(this.editor.getLineStartOffset(line));
            if (bounds == null || y < bounds.getY() || y >= bounds.getY() + this.editor.getLineHeight()) {
                return -1;
            }
            return line + 1;
        } catch (BadLocationException exception) {
            throw new IllegalStateException("Unable to resolve a displayed editor line", exception);
        }
    }

    private static LineNumberList findLineNumbers(Gutter gutter) {
        for (Component child : gutter.getComponents()) {
            if (child instanceof LineNumberList lineNumbers) {
                return lineNumbers;
            }
        }
        throw new IllegalStateException("RSyntaxTextArea gutter has no line numbers");
    }

    private void clear() {
        for (GutterIconInfo marker : this.installed) {
            this.gutter.removeTrackingIcon(marker);
        }
        this.installed.clear();
    }
}
