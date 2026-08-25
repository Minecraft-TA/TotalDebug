package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.insight.HierarchyFacet;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.insight.HierarchyRelation;
import com.github.minecraft_ta.totalDebugCompanion.jdt.symbol.CodeSymbol;
import com.github.minecraft_ta.totalDebugCompanion.ui.HierarchyPresentation;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rtextarea.RTextScrollPane;

import javax.swing.JComponent;
import javax.swing.JLayer;
import javax.swing.SwingUtilities;
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
import java.util.List;
import java.util.Objects;

/** Paints clickable code-vision counts without inserting text into the decompiled document. */
final class CodeVisionLayerUI extends LayerUI<RTextScrollPane> {
    interface Handler {
        void showUsages(CodeSymbol symbol);

        void showHierarchy(CodeSymbol symbol, HierarchyRelation relation, int count, int anchorOffset);
    }

    private enum Action {
        USAGES,
        IMPLEMENTATIONS
    }

    private final RSyntaxTextArea editor;
    private final Handler handler;
    private final List<HitTarget> hitTargets = new ArrayList<>();
    private List<CodeVisionEntry> entries = List.of();
    private HitTarget hovered;

    CodeVisionLayerUI(RSyntaxTextArea editor, Handler handler) {
        this.editor = Objects.requireNonNull(editor, "editor");
        this.handler = Objects.requireNonNull(handler, "handler");
    }

    void setEntries(List<CodeVisionEntry> entries, JLayer<RTextScrollPane> layer) {
        this.entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
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
        JLayer<RTextScrollPane> layer = (JLayer<RTextScrollPane>) component;
        Graphics2D draw = (Graphics2D) graphics.create();
        try {
            draw.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Font editorFont = this.editor.getFont();
            Font hintFont = editorFont.deriveFont(Math.max(10f, editorFont.getSize2D() - 2f));
            draw.setFont(hintFont);
            FontMetrics metrics = draw.getFontMetrics();
            this.hitTargets.clear();

            for (CodeVisionEntry entry : this.entries) {
                paintEntry(draw, layer, metrics, entry);
            }
        } finally {
            draw.dispose();
        }
    }

    private void paintEntry(
            Graphics2D draw,
            JLayer<RTextScrollPane> layer,
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
                return;
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
                paintSegment(
                        draw,
                        metrics,
                        entry,
                        Action.IMPLEMENTATIONS,
                        HierarchyPresentation.codeVisionCount(descendants.relation(), descendants.count()),
                        x,
                        baseline
                );
            }
        } catch (BadLocationException ignored) {
        }
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
        HitTarget target = new HitTarget(bounds, entry, action);
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
    protected void processMouseMotionEvent(MouseEvent event, JLayer<? extends RTextScrollPane> layer) {
        HitTarget target = targetAt(pointInLayer(event, layer));
        if (!Objects.equals(target, this.hovered)) {
            this.hovered = target;
            layer.setCursor(target == null ? Cursor.getDefaultCursor() : Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            layer.setToolTipText(target == null ? null : target.tooltip());
            layer.repaint();
        }
    }

    @Override
    protected void processMouseEvent(MouseEvent event, JLayer<? extends RTextScrollPane> layer) {
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
        HitTarget target = targetAt(pointInLayer(event, layer));
        if (target == null) {
            return;
        }
        switch (target.action()) {
            case USAGES -> this.handler.showUsages(target.entry().declaration().symbol());
            case IMPLEMENTATIONS -> {
                HierarchyFacet facet = target.entry().insight().descendantFacet().orElseThrow();
                this.handler.showHierarchy(
                    target.entry().declaration().symbol(),
                    facet.relation(),
                    facet.count(),
                    target.entry().declaration().anchorOffset()
                );
            }
        }
        event.consume();
    }

    private static Point pointInLayer(MouseEvent event, JLayer<? extends RTextScrollPane> layer) {
        return SwingUtilities.convertPoint(event.getComponent(), event.getPoint(), layer);
    }

    private HitTarget targetAt(Point point) {
        for (HitTarget target : this.hitTargets) {
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

    private record HitTarget(Rectangle bounds, CodeVisionEntry entry, Action action) {
        private String tooltip() {
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
}
