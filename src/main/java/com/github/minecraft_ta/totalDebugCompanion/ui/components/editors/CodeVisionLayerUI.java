package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.insight.HierarchyFacet;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.insight.HierarchyRelation;
import com.github.minecraft_ta.totalDebugCompanion.jdt.symbol.CodeSymbol;
import com.github.minecraft_ta.totalDebugCompanion.ui.HierarchyPresentation;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;

import javax.swing.JComponent;
import javax.swing.JLayer;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.plaf.LayerUI;
import javax.swing.text.BadLocationException;
import java.awt.AWTEvent;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseEvent;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Paints clickable editor adornments without inserting text into the decompiled document. */
final class CodeVisionLayerUI extends LayerUI<JComponent> {
    private static final int INLINE_VALUE_GAP = 18;

    interface Handler {
        void showUsages(CodeSymbol symbol);

        void showHierarchy(CodeSymbol symbol, HierarchyRelation relation, int count, int anchorOffset);

        void showDebuggerValue(DebuggerInlineValueHints.ValueHint value);
    }

    private enum Action {
        USAGES,
        IMPLEMENTATIONS
    }

    private final RSyntaxTextArea editor;
    private final Handler handler;
    private final List<Target> hitTargets = new ArrayList<>();
    private List<CodeVisionEntry> entries = List.of();
    private Map<Integer, DebuggerInlineValueHints.LineHint> inlineValues = Map.of();
    private Target hovered;

    CodeVisionLayerUI(RSyntaxTextArea editor, Handler handler) {
        this.editor = Objects.requireNonNull(editor, "editor");
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    static int inlineValueStart(int lineEnd, int codeVisionEnd) {
        return Math.max(lineEnd, codeVisionEnd) + INLINE_VALUE_GAP;
    }

    void setEntries(List<CodeVisionEntry> entries, JLayer<JComponent> layer) {
        this.entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        this.hovered = null;
        this.hitTargets.clear();
        layer.repaint();
    }

    void setInlineValues(
            Map<Integer, DebuggerInlineValueHints.LineHint> inlineValues,
            JLayer<JComponent> layer
    ) {
        this.inlineValues = Map.copyOf(Objects.requireNonNull(inlineValues, "inlineValues"));
        this.hovered = null;
        this.hitTargets.clear();
        layer.repaint();
    }

    @Override
    public void installUI(JComponent component) {
        super.installUI(component);
        JLayer<?> layer = (JLayer<?>) component;
        layer.setLayerEventMask(AWTEvent.MOUSE_EVENT_MASK | AWTEvent.MOUSE_MOTION_EVENT_MASK);
        layer.setToolTipText(null);
    }

    @Override
    public void uninstallUI(JComponent component) {
        ((JLayer<?>) component).setLayerEventMask(0);
        super.uninstallUI(component);
    }

    @Override
    public void paint(Graphics graphics, JComponent component) {
        super.paint(graphics, component);
        @SuppressWarnings("unchecked")
        JLayer<JComponent> layer = (JLayer<JComponent>) component;
        Graphics2D draw = (Graphics2D) graphics.create();
        try {
            draw.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Font uiFont = UIManager.getFont("Label.font");
            Font hintFont = uiFont == null ? this.editor.getFont() : uiFont;
            draw.setFont(hintFont);
            FontMetrics metrics = draw.getFontMetrics();
            this.hitTargets.clear();
            Map<Integer, Integer> codeVisionEnds = new HashMap<>();

            for (CodeVisionEntry entry : this.entries) {
                LineEnd lineEnd = paintEntry(draw, layer, metrics, entry);
                if (lineEnd != null) {
                    codeVisionEnds.merge(lineEnd.line(), lineEnd.x(), Math::max);
                }
            }
            paintInlineValues(draw, layer, codeVisionEnds);
        } finally {
            draw.dispose();
        }
    }

    private LineEnd paintEntry(
            Graphics2D draw,
            JLayer<JComponent> layer,
            FontMetrics metrics,
            CodeVisionEntry entry
    ) {
        try {
            Rectangle2D anchor = this.editor.modelToView2D(entry.declaration().anchorOffset());
            Point point = SwingUtilities.convertPoint(
                    this.editor,
                    (int) Math.ceil(anchor.getX()),
                    (int) Math.floor(anchor.getY()),
                    layer
            );
            Rectangle visible = layer.getVisibleRect();
            if (point.y + anchor.getHeight() < visible.y || point.y > visible.y + visible.height) {
                return null;
            }

            int x = point.x + 10;
            int baseline = point.y
                    + ((int) Math.ceil(anchor.getHeight()) - metrics.getHeight()) / 2
                    + metrics.getAscent();
            if (entry.insight().usageCount() > 0) {
                x = paintSegment(
                        draw,
                        metrics,
                        entry,
                        Action.USAGES,
                        formatCount(entry.insight().usageCount(), "usage", "usages"),
                        x,
                        baseline
                );
            }
            HierarchyFacet descendants = entry.insight().descendantFacet().orElse(null);
            if (descendants != null) {
                if (x > point.x + 10) {
                    x += 12;
                }
                x = paintSegment(
                        draw,
                        metrics,
                        entry,
                        Action.IMPLEMENTATIONS,
                        HierarchyPresentation.codeVisionCount(descendants.relation(), descendants.count()),
                        x,
                        baseline
                );
            }
            return x == point.x + 10
                    ? null
                    : new LineEnd(this.editor.getLineOfOffset(entry.declaration().anchorOffset()) + 1, x);
        } catch (BadLocationException ignored) {
            return null;
        }
    }

    private void paintInlineValues(
            Graphics2D draw,
            JLayer<JComponent> layer,
            Map<Integer, Integer> codeVisionEnds
    ) {
        if (this.inlineValues.isEmpty()) {
            return;
        }
        Rectangle visible = this.editor.getVisibleRect();
        Point visibleOrigin = SwingUtilities.convertPoint(this.editor, visible.x, visible.y, layer);
        Rectangle clip = new Rectangle(visibleOrigin.x, visibleOrigin.y, visible.width, visible.height);
        Graphics2D hints = (Graphics2D) draw.create();
        try {
            hints.clip(clip);
            hints.setFont(this.editor.getFont().deriveFont(Font.ITALIC));
            hints.setColor(ThemeColors.mutedText());
            FontMetrics metrics = hints.getFontMetrics();
            for (Map.Entry<Integer, DebuggerInlineValueHints.LineHint> entry : this.inlineValues.entrySet()) {
                int line = entry.getKey();
                if (line < 1 || line > this.editor.getLineCount()) {
                    continue;
                }
                int start = this.editor.getLineStartOffset(line - 1);
                int end = this.editor.getLineEndOffset(line - 1);
                while (end > start && Character.isWhitespace(this.editor.getText(end - 1, 1).charAt(0))) {
                    end--;
                }
                Rectangle2D endBounds = this.editor.modelToView2D(end);
                Point point = SwingUtilities.convertPoint(
                        this.editor,
                        (int) Math.ceil(endBounds.getX()),
                        (int) Math.floor(endBounds.getY()),
                        layer
                );
                point.x = inlineValueStart(point.x, codeVisionEnds.getOrDefault(line, Integer.MIN_VALUE));
                if (point.y + endBounds.getHeight() < clip.y || point.y > clip.y + clip.height) {
                    continue;
                }
                int baseline = point.y + ((int) Math.ceil(endBounds.getHeight()) - metrics.getHeight()) / 2
                        + metrics.getAscent();
                int x = point.x;
                int separator = metrics.stringWidth("    ");
                for (DebuggerInlineValueHints.ValueHint value : entry.getValue().values()) {
                    int available = clip.x + clip.width - x - 8;
                    if (available <= metrics.charWidth('…')) {
                        break;
                    }
                    String text = fit(value.text(), metrics, available);
                    int width = metrics.stringWidth(text);
                    Rectangle bounds = new Rectangle(
                            x - 2,
                            baseline - metrics.getAscent(),
                            width + 4,
                            metrics.getHeight()
                    );
                    InlineValueTarget target = new InlineValueTarget(bounds, value);
                    hints.setColor(target.equals(this.hovered) ? ThemeColors.accent() : ThemeColors.mutedText());
                    hints.drawString(text, x, baseline);
                    if (target.equals(this.hovered)) {
                        hints.drawLine(x, baseline + 1, x + width - 1, baseline + 1);
                    }
                    this.hitTargets.add(target);
                    x += width + separator;
                    if (!text.equals(value.text())) {
                        break;
                    }
                }
            }
        } catch (BadLocationException ignored) {
        } finally {
            hints.dispose();
        }
    }

    private static String fit(String text, FontMetrics metrics, int width) {
        if (metrics.stringWidth(text) <= width) {
            return text;
        }
        String suffix = "…";
        int low = 0;
        int high = text.length();
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (metrics.stringWidth(text.substring(0, middle) + suffix) <= width) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return text.substring(0, low) + suffix;
    }

    private int paintSegment(
            Graphics2D draw,
            FontMetrics metrics,
            CodeVisionEntry entry,
            Action action,
            String text,
            int x,
            int baseline
    ) {
        int width = metrics.stringWidth(text);
        Rectangle bounds = new Rectangle(x - 2, baseline - metrics.getAscent(), width + 4, metrics.getHeight());
        CodeVisionTarget target = new CodeVisionTarget(bounds, entry, action);
        Color color = target.equals(this.hovered)
                ? ThemeColors.accent()
                : blend(ThemeColors.mutedText(), ThemeColors.text(), 0.35f);
        draw.setColor(color);
        draw.drawString(text, x, baseline);
        if (target.equals(this.hovered)) {
            draw.drawLine(x, baseline + 1, x + width - 1, baseline + 1);
        }
        this.hitTargets.add(target);
        return x + width;
    }

    @Override
    protected void processMouseMotionEvent(MouseEvent event, JLayer<? extends JComponent> layer) {
        Target target = targetAt(pointInLayer(event, layer));
        if (!Objects.equals(target, this.hovered)) {
            this.hovered = target;
            layer.setCursor(target == null ? Cursor.getDefaultCursor() : Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            layer.setToolTipText(target == null ? null : target.tooltip());
            layer.repaint();
        }
    }

    @Override
    protected void processMouseEvent(MouseEvent event, JLayer<? extends JComponent> layer) {
        if (event.getID() == MouseEvent.MOUSE_EXITED) {
            this.hovered = null;
            layer.setCursor(Cursor.getDefaultCursor());
            layer.setToolTipText(null);
            layer.repaint();
            return;
        }
        if (event.getID() != MouseEvent.MOUSE_CLICKED || !SwingUtilities.isLeftMouseButton(event)) {
            return;
        }
        Target target = targetAt(pointInLayer(event, layer));
        if (target == null) {
            return;
        }
        switch (target) {
            case CodeVisionTarget codeVision -> {
                switch (codeVision.action()) {
                    case USAGES -> this.handler.showUsages(codeVision.entry().declaration().symbol());
                    case IMPLEMENTATIONS -> {
                        HierarchyFacet facet = codeVision.entry().insight().descendantFacet().orElseThrow();
                        this.handler.showHierarchy(
                                codeVision.entry().declaration().symbol(),
                                facet.relation(),
                                facet.count(),
                                codeVision.entry().declaration().anchorOffset()
                        );
                    }
                }
            }
            case InlineValueTarget inline -> this.handler.showDebuggerValue(inline.value());
        }
        event.consume();
    }

    private static Point pointInLayer(MouseEvent event, JLayer<? extends JComponent> layer) {
        return SwingUtilities.convertPoint(event.getComponent(), event.getPoint(), layer);
    }

    private Target targetAt(Point point) {
        for (Target target : this.hitTargets) {
            if (target.bounds().contains(point)) {
                return target;
            }
        }
        return null;
    }

    private static String formatCount(long count, String singular, String plural) {
        return count + " " + (count == 1 ? singular : plural);
    }

    private static Color blend(Color from, Color to, float amount) {
        float inverse = 1f - amount;
        return new Color(
                Math.round(from.getRed() * inverse + to.getRed() * amount),
                Math.round(from.getGreen() * inverse + to.getGreen() * amount),
                Math.round(from.getBlue() * inverse + to.getBlue() * amount)
        );
    }

    private sealed interface Target permits CodeVisionTarget, InlineValueTarget {
        Rectangle bounds();

        String tooltip();
    }

    private record CodeVisionTarget(Rectangle bounds, CodeVisionEntry entry, Action action) implements Target {
        @Override
        public String tooltip() {
            return switch (this.action) {
                case USAGES -> "Find usages of " + this.entry.declaration().symbol().displayName();
                case IMPLEMENTATIONS -> {
                    HierarchyFacet facet = this.entry.insight().descendantFacet().orElseThrow();
                    yield "Show " + HierarchyPresentation.codeVisionCount(facet.relation(), facet.count())
                            + " of " + this.entry.declaration().symbol().displayName();
                }
            };
        }
    }

    private record InlineValueTarget(
            Rectangle bounds,
            DebuggerInlineValueHints.ValueHint value
    ) implements Target {
        @Override
        public String tooltip() {
            return "Show " + this.value.value().variable().name() + " in debugger";
        }
    }

    private record LineEnd(int line, int x) {
    }
}
