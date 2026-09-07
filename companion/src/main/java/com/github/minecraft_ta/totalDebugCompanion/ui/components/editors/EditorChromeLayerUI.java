package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.ui.theme.EditorPalette;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rtextarea.Gutter;
import org.fife.ui.rtextarea.RTextScrollPane;

import javax.swing.JComponent;
import javax.swing.JLayer;
import javax.swing.SwingUtilities;
import javax.swing.event.CaretEvent;
import javax.swing.event.CaretListener;
import javax.swing.plaf.LayerUI;
import javax.swing.text.BadLocationException;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.geom.Rectangle2D;
import java.util.Objects;

/** Paints editor-wide chrome that must span RSyntaxTextArea's separate gutter children. */
final class EditorChromeLayerUI extends LayerUI<RTextScrollPane> implements CaretListener {
    private final RSyntaxTextArea editor;
    private final Gutter gutter;
    private JLayer<RTextScrollPane> layer;
    private Color gutterBackground;
    private Color currentLine;
    private BreakpointGutterMarkers breakpointMarkers;

    EditorChromeLayerUI(RSyntaxTextArea editor, Gutter gutter) {
        this.editor = Objects.requireNonNull(editor, "editor");
        this.gutter = Objects.requireNonNull(gutter, "gutter");
    }

    void setPalette(EditorPalette palette, JLayer<RTextScrollPane> layer) {
        this.gutterBackground = palette.background();
        this.currentLine = palette.currentLine();
        layer.repaint();
    }

    void setBreakpointMarkers(BreakpointGutterMarkers breakpointMarkers) {
        this.breakpointMarkers = breakpointMarkers;
        if (this.layer != null) {
            this.layer.repaint();
        }
    }

    @Override
    public void installUI(JComponent component) {
        super.installUI(component);
        @SuppressWarnings("unchecked")
        JLayer<RTextScrollPane> installedLayer = (JLayer<RTextScrollPane>) component;
        this.layer = installedLayer;
        this.editor.addCaretListener(this);
    }

    @Override
    public void uninstallUI(JComponent component) {
        this.editor.removeCaretListener(this);
        this.layer = null;
        super.uninstallUI(component);
    }

    @Override
    public void caretUpdate(CaretEvent event) {
        if (this.layer != null) {
            this.layer.repaint();
        }
    }

    @Override
    public void paint(Graphics graphics, JComponent component) {
        Graphics2D draw = (Graphics2D) graphics.create();
        try {
            Rectangle gutterBounds = componentBounds(this.gutter, component);
            draw.setColor(this.gutterBackground == null ? this.editor.getBackground() : this.gutterBackground);
            draw.fillRect(gutterBounds.x, gutterBounds.y, gutterBounds.width, gutterBounds.height);

            Rectangle2D caretLine = this.editor.modelToView2D(this.editor.getCaretPosition());
            Point linePoint = SwingUtilities.convertPoint(
                    this.editor,
                    0,
                    (int) Math.floor(caretLine.getY()),
                    component
            );
            draw.setColor(this.currentLine == null ? this.editor.getCurrentLineHighlightColor() : this.currentLine);
            draw.fillRect(
                    gutterBounds.x,
                    linePoint.y,
                    gutterBounds.width,
                    (int) Math.ceil(caretLine.getHeight())
            );
        } catch (BadLocationException ignored) {
        } finally {
            draw.dispose();
        }
        super.paint(graphics, component);
        if (this.breakpointMarkers != null) {
            Graphics2D overlay = (Graphics2D) graphics.create();
            try {
                this.breakpointMarkers.paint(
                        overlay,
                        component,
                        this.gutterBackground == null ? this.editor.getBackground() : this.gutterBackground,
                        this.currentLine == null ? this.editor.getCurrentLineHighlightColor() : this.currentLine
                );
            } finally {
                overlay.dispose();
            }
        }
    }

    private static Rectangle componentBounds(JComponent child, JComponent ancestor) {
        Point point = SwingUtilities.convertPoint(child, 0, 0, ancestor);
        return new Rectangle(point.x, point.y, child.getWidth(), child.getHeight());
    }
}
