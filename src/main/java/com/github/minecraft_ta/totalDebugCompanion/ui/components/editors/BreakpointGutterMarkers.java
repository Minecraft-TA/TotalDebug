package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerSessionController;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rtextarea.LineNumberList;

import javax.swing.Icon;
import javax.swing.JLayer;
import javax.swing.SwingUtilities;
import javax.swing.plaf.LayerUI;
import javax.swing.text.BadLocationException;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.geom.Area;
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

        void configure(int displayedLine, Component invoker, Point location);
    }

    private final RSyntaxTextArea editor;
    private final Handler handler;
    private final LineNumberList lineNumbers;
    private final JLayer<LineNumberList> lineNumberLayer;
    private final BreakpointLayerUI layerUI = new BreakpointLayerUI();
    private List<DebuggerSessionController.Breakpoint> breakpoints = List.of();

    private final MouseAdapter lineNumberClicks = new MouseAdapter() {
        @Override
        public void mouseClicked(MouseEvent event) {
            if (!SwingUtilities.isLeftMouseButton(event) || event.getClickCount() != 1) {
                return;
            }
            int displayedLine = displayedLineAt(event.getY());
            if (displayedLine > 0) {
                handler.toggle(displayedLine);
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

    BreakpointGutterMarkers(EditorGutter gutter, RSyntaxTextArea editor, Handler handler) {
        Objects.requireNonNull(gutter, "gutter");
        this.editor = Objects.requireNonNull(editor, "editor");
        this.handler = Objects.requireNonNull(handler, "handler");
        this.lineNumbers = gutter.lineNumbers();
        this.lineNumberLayer = gutter.lineNumberLayer();
        this.lineNumberLayer.setUI(this.layerUI);
        this.lineNumbers.addMouseListener(this.lineNumberClicks);
        this.lineNumbers.addMouseMotionListener(this.tooltipUpdater);
    }

    void setBreakpoints(List<DebuggerSessionController.Breakpoint> breakpoints) {
        this.breakpoints = List.copyOf(Objects.requireNonNull(breakpoints, "breakpoints"));
        this.lineNumberLayer.repaint();
    }

    void dispose() {
        this.lineNumbers.removeMouseListener(this.lineNumberClicks);
        this.lineNumbers.removeMouseMotionListener(this.tooltipUpdater);
        this.lineNumbers.setToolTipText(null);
        this.lineNumberLayer.setUI(new LayerUI<>());
    }

    static Icon iconFor(DebuggerSessionController.Breakpoint breakpoint) {
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

    private final class BreakpointLayerUI extends LayerUI<LineNumberList> {
        @Override
        public void paint(Graphics graphics, javax.swing.JComponent component) {
            Graphics2D base = (Graphics2D) graphics.create();
            Shape originalClip = base.getClip();
            Area remaining = originalClip == null
                    ? new Area(new Rectangle(0, 0, component.getWidth(), component.getHeight()))
                    : new Area(originalClip);
            for (DebuggerSessionController.Breakpoint breakpoint : breakpoints) {
                Rectangle row = rowBounds(breakpoint.line());
                if (row != null) {
                    remaining.subtract(new Area(row));
                }
            }
            base.setClip(remaining);
            super.paint(base, component);
            base.dispose();

            Graphics2D icons = (Graphics2D) graphics.create();
            for (DebuggerSessionController.Breakpoint breakpoint : breakpoints) {
                Rectangle row = rowBounds(breakpoint.line());
                if (row == null) {
                    continue;
                }
                Icon icon = iconFor(breakpoint);
                int x = Math.max(0, (component.getWidth() - icon.getIconWidth()) / 2);
                int y = row.y + Math.max(0, (row.height - icon.getIconHeight()) / 2);
                icon.paintIcon(component, icons, x, y);
            }
            icons.dispose();
        }

        private Rectangle rowBounds(int displayedLine) {
            if (displayedLine < 1 || displayedLine > editor.getLineCount()) {
                return null;
            }
            try {
                var bounds = editor.modelToView2D(editor.getLineStartOffset(displayedLine - 1));
                if (bounds == null) {
                    return null;
                }
                return new Rectangle(0, (int) bounds.getY(), lineNumberLayer.getWidth(), editor.getLineHeight());
            } catch (BadLocationException exception) {
                throw new IllegalStateException("Unable to paint breakpoint line " + displayedLine, exception);
            }
        }
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
