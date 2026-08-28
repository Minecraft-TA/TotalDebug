package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerSessionController;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rtextarea.LineNumberList;
import org.fife.ui.rtextarea.RTextScrollPane;

import javax.swing.Icon;
import javax.swing.JLayer;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Paints and handles breakpoints directly in the line-number column. */
final class BreakpointGutterMarkers {
    private static final Icon CONDITIONAL_BREAKPOINT = new BadgeIcon(
            Icons.BREAKPOINT,
            Icons.BREAKPOINT_QUESTION_BADGE
    );
    private static final Icon CONDITIONAL_BREAKPOINT_VALID = new BadgeIcon(
            Icons.BREAKPOINT_VALID,
            Icons.BREAKPOINT_QUESTION_BADGE
    );
    private static final Icon CONDITIONAL_METHOD_BREAKPOINT = new BadgeIcon(
            Icons.BREAKPOINT_METHOD,
            Icons.BREAKPOINT_QUESTION_BADGE
    );
    private static final Icon CONDITIONAL_METHOD_BREAKPOINT_VALID = new BadgeIcon(
            Icons.BREAKPOINT_METHOD_VALID,
            Icons.BREAKPOINT_QUESTION_BADGE
    );

    interface Handler {
        void toggle(int displayedLine);

        void toggleEnabled(int displayedLine);

        void configure(int displayedLine, Component invoker, Point location);
    }

    private final RSyntaxTextArea editor;
    private final Handler handler;
    private final LineNumberList lineNumbers;
    private final JLayer<RTextScrollPane> paintLayer;
    private final boolean restoreNativeMouseListener;
    private final boolean restoreNativeMouseMotionListener;
    private List<DebuggerSessionController.Breakpoint> breakpoints = List.of();

    private final MouseAdapter lineNumberClicks = new MouseAdapter() {
        @Override
        public void mouseClicked(MouseEvent event) {
            if (!SwingUtilities.isLeftMouseButton(event) || event.getClickCount() != 1) {
                return;
            }
            int displayedLine = displayedLineAt(event.getY());
            if (displayedLine > 0) {
                if (event.isAltDown()) {
                    if (breakpointAt(displayedLine) != null) {
                        handler.toggleEnabled(displayedLine);
                    }
                } else {
                    handler.toggle(displayedLine);
                }
            }
        }

        @Override
        public void mousePressed(MouseEvent event) {
            showConfigurationIfRequested(event);
        }

        @Override
        public void mouseReleased(MouseEvent event) {
            showConfigurationIfRequested(event);
        }
    };
    private final MouseMotionAdapter tooltipUpdater = new MouseMotionAdapter() {
        @Override
        public void mouseMoved(MouseEvent event) {
            int displayedLine = displayedLineAt(event.getY());
            DebuggerSessionController.Breakpoint breakpoint = breakpointAt(displayedLine);
            lineNumbers.setToolTipText(breakpoint == null ? null : tooltipFor(breakpoint));
        }
    };

    BreakpointGutterMarkers(
            EditorGutter gutter,
            RSyntaxTextArea editor,
            JLayer<RTextScrollPane> paintLayer,
            Handler handler
    ) {
        Objects.requireNonNull(gutter, "gutter");
        this.editor = Objects.requireNonNull(editor, "editor");
        this.paintLayer = Objects.requireNonNull(paintLayer, "paintLayer");
        this.handler = Objects.requireNonNull(handler, "handler");
        this.lineNumbers = gutter.lineNumbers();
        this.restoreNativeMouseListener = Arrays.stream(this.lineNumbers.getMouseListeners())
                .anyMatch(listener -> listener == this.lineNumbers);
        this.restoreNativeMouseMotionListener = Arrays.stream(this.lineNumbers.getMouseMotionListeners())
                .anyMatch(listener -> listener == this.lineNumbers);
        this.lineNumbers.removeMouseListener(this.lineNumbers);
        this.lineNumbers.removeMouseMotionListener(this.lineNumbers);
        this.lineNumbers.addMouseListener(this.lineNumberClicks);
        this.lineNumbers.addMouseMotionListener(this.tooltipUpdater);
    }

    void setBreakpoints(List<DebuggerSessionController.Breakpoint> breakpoints) {
        this.breakpoints = List.copyOf(Objects.requireNonNull(breakpoints, "breakpoints"));
        this.paintLayer.repaint();
    }

    void dispose() {
        this.lineNumbers.removeMouseListener(this.lineNumberClicks);
        this.lineNumbers.removeMouseMotionListener(this.tooltipUpdater);
        if (this.restoreNativeMouseListener) {
            this.lineNumbers.addMouseListener(this.lineNumbers);
        }
        if (this.restoreNativeMouseMotionListener) {
            this.lineNumbers.addMouseMotionListener(this.lineNumbers);
        }
        this.lineNumbers.setToolTipText(null);
        this.breakpoints = List.of();
        this.paintLayer.repaint();
    }

    void paint(Graphics2D graphics, Component layer, Color gutterBackground, Color currentLine) {
        Rectangle lineNumberBounds = componentBounds(this.lineNumbers, layer);
        for (DebuggerSessionController.Breakpoint breakpoint : this.breakpoints) {
            Rectangle row = rowBounds(breakpoint.line(), layer, lineNumberBounds);
            if (row == null) {
                continue;
            }
            graphics.setColor(breakpoint.line() == this.editor.getCaretLineNumber() + 1
                    ? currentLine
                    : gutterBackground);
            graphics.fillRect(row.x, row.y, row.width, row.height);
            Icon icon = iconFor(breakpoint);
            int x = row.x + Math.max(0, (row.width - icon.getIconWidth()) / 2);
            int y = row.y + Math.max(0, (row.height - icon.getIconHeight()) / 2);
            icon.paintIcon(layer, graphics, x, y);
        }
    }

    static Icon iconFor(DebuggerSessionController.Breakpoint breakpoint) {
        if (breakpoint.state() == DebuggerSessionController.BreakpointState.DISABLED) {
            return Icons.BREAKPOINT_DISABLED;
        }
        if (breakpoint.state() == DebuggerSessionController.BreakpointState.INVALID) {
            return Icons.BREAKPOINT_INVALID;
        }
        boolean bound = breakpoint.state() == DebuggerSessionController.BreakpointState.BOUND;
        boolean method = breakpoint.request().isMethodEntry();
        if (isConditional(breakpoint.request())) {
            if (method) {
                return bound ? CONDITIONAL_METHOD_BREAKPOINT_VALID : CONDITIONAL_METHOD_BREAKPOINT;
            }
            return bound ? CONDITIONAL_BREAKPOINT_VALID : CONDITIONAL_BREAKPOINT;
        }
        if (method) {
            return bound ? Icons.BREAKPOINT_METHOD_VALID : Icons.BREAKPOINT_METHOD;
        }
        return bound ? Icons.BREAKPOINT_VALID : Icons.BREAKPOINT;
    }

    private void showConfigurationIfRequested(MouseEvent event) {
        if (!event.isPopupTrigger()) {
            return;
        }
        int displayedLine = displayedLineAt(event.getY());
        if (displayedLine <= 0) {
            return;
        }
        try {
            Rectangle row = this.editor.modelToView2D(this.editor.getLineStartOffset(displayedLine - 1)).getBounds();
            this.handler.configure(
                    displayedLine,
                    this.lineNumbers,
                    new Point(this.lineNumbers.getWidth() + 6, row.y)
            );
        } catch (BadLocationException exception) {
            throw new IllegalStateException("Unable to locate breakpoint line " + displayedLine, exception);
        }
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

    private DebuggerSessionController.Breakpoint breakpointAt(int displayedLine) {
        if (displayedLine < 1) {
            return null;
        }
        for (DebuggerSessionController.Breakpoint breakpoint : this.breakpoints) {
            if (breakpoint.line() == displayedLine) {
                return breakpoint;
            }
        }
        return null;
    }

    private static boolean isConditional(DebugEngine.SourceBreakpoint breakpoint) {
        return breakpoint.condition() != null && !breakpoint.condition().isBlank()
                || breakpoint.hitCondition() != null && !breakpoint.hitCondition().isBlank();
    }

    private static String tooltipFor(DebuggerSessionController.Breakpoint breakpoint) {
        if (breakpoint.state() == DebuggerSessionController.BreakpointState.INVALID) {
            return breakpoint.detail();
        }
        DebugEngine.SourceBreakpoint request = breakpoint.request();
        String state = switch (breakpoint.state()) {
            case DISABLED -> breakpoint.request().isMethodEntry()
                    ? "Method breakpoint disabled"
                    : "Breakpoint disabled";
            case UNBOUND -> breakpoint.request().isMethodEntry()
                    ? "Method breakpoint not bound"
                    : "Breakpoint not bound";
            case PENDING -> breakpoint.request().isMethodEntry()
                    ? "Method breakpoint pending"
                    : "Breakpoint pending";
            case BOUND -> breakpoint.request().isMethodEntry()
                    ? "Method breakpoint"
                    : "Breakpoint";
            case INVALID -> throw new AssertionError("Handled above");
        };
        if (request.condition() != null && !request.condition().isBlank()) {
            return state + ", condition: " + request.condition();
        }
        if (request.hitCondition() != null && !request.hitCondition().isBlank()) {
            return state + " after " + request.hitCondition() + " hits";
        }
        return state + " at line " + breakpoint.line();
    }

    private Rectangle rowBounds(int displayedLine, Component layer, Rectangle lineNumberBounds) {
        if (displayedLine < 1 || displayedLine > this.editor.getLineCount()) {
            return null;
        }
        try {
            var bounds = this.editor.modelToView2D(this.editor.getLineStartOffset(displayedLine - 1));
            if (bounds == null) {
                return null;
            }
            Point point = SwingUtilities.convertPoint(
                    this.editor,
                    0,
                    (int) bounds.getY(),
                    layer
            );
            return new Rectangle(
                    lineNumberBounds.x,
                    point.y,
                    lineNumberBounds.width,
                    this.editor.getLineHeight()
            );
        } catch (BadLocationException exception) {
            throw new IllegalStateException("Unable to paint breakpoint line " + displayedLine, exception);
        }
    }

    private static Rectangle componentBounds(Component child, Component ancestor) {
        Point point = SwingUtilities.convertPoint(child, 0, 0, ancestor);
        return new Rectangle(point.x, point.y, child.getWidth(), child.getHeight());
    }

    private record BadgeIcon(Icon breakpoint, Icon badge) implements Icon {
        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            this.breakpoint.paintIcon(
                    component,
                    graphics,
                    x,
                    y + (getIconHeight() - this.breakpoint.getIconHeight()) / 2
            );
            this.badge.paintIcon(
                    component,
                    graphics,
                    x + getIconWidth() - this.badge.getIconWidth(),
                    y + getIconHeight() - this.badge.getIconHeight()
            );
        }

        @Override
        public int getIconWidth() {
            return Math.max(this.breakpoint.getIconWidth(), this.badge.getIconWidth());
        }

        @Override
        public int getIconHeight() {
            return Math.max(this.breakpoint.getIconHeight(), this.badge.getIconHeight());
        }
    }
}
