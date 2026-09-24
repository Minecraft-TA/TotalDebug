package com.github.minecraft_ta.totalDebugCompanion.ui.components.inspection;

import javax.swing.JComponent;
import javax.swing.UIManager;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.TexturePaint;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;

/**
 * A filled amount such as stored energy or a tank's contents. The fill is a texture tiled at the bar's height when one
 * is set, for example the fluid's own still texture, otherwise the theme's progress color. Text stays readable over
 * any fill through an outline. A changed bar is outlined in the accent color until its next update.
 */
final class AmountBar extends JComponent {
    private static final int WIDTH = 320;
    private static final int HEIGHT = 20;
    private static final int ARC = 6;

    private long amount;
    private long capacity;
    private String text;
    private BufferedImage texture;
    private boolean changed;

    AmountBar(long amount, long capacity, String text) {
        Dimension size = new Dimension(WIDTH, HEIGHT);
        setPreferredSize(size);
        setMinimumSize(size);
        set(amount, capacity, text, false);
    }

    /** Shows newer values; {@code changed} marks them as different from the previous read. */
    void set(long amount, long capacity, String text, boolean changed) {
        this.amount = amount;
        this.capacity = capacity;
        this.text = text;
        this.changed = changed;
        setToolTipText(text);
        repaint();
    }

    String text() {
        return this.text;
    }

    boolean changed() {
        return this.changed;
    }

    void setTexture(BufferedImage texture) {
        this.texture = texture;
        repaint();
    }

    double fraction() {
        return this.capacity <= 0 ? 0 : Math.min(1.0, this.amount / (double) this.capacity);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int width = getWidth();
            int height = getHeight();
            Shape track = new RoundRectangle2D.Float(0, 0, width - 1, height - 1, ARC, ARC);
            g.setColor(UIManager.getColor("ProgressBar.background"));
            g.fill(track);

            int filled = (int) Math.round((width - 1) * fraction());
            if (filled > 0) {
                Shape oldClip = g.getClip();
                g.clip(track);
                g.clipRect(0, 0, filled, height);
                if (this.texture != null) {
                    g.setPaint(new TexturePaint(this.texture, new Rectangle(0, 0, height, height)));
                } else {
                    g.setColor(UIManager.getColor("ProgressBar.foreground"));
                }
                g.fillRect(0, 0, filled, height);
                g.setClip(oldClip);
            }
            if (this.changed) {
                g.setColor(ChangeMarks.color());
                g.setStroke(new BasicStroke(2f));
                g.draw(new RoundRectangle2D.Float(1, 1, width - 3, height - 3, ARC, ARC));
            } else {
                g.setColor(UIManager.getColor("Component.borderColor"));
                g.draw(track);
            }
            paintText(g, width, height);
        } finally {
            g.dispose();
        }
    }

    private void paintText(Graphics2D g, int width, int height) {
        Font font = getFont() == null ? UIManager.getFont("Label.font") : getFont();
        g.setFont(font);
        int textWidth = g.getFontMetrics().stringWidth(this.text);
        int x = Math.max(6, (width - textWidth) / 2);
        int y = (height - g.getFontMetrics().getHeight()) / 2 + g.getFontMetrics().getAscent();
        g.setColor(new Color(0, 0, 0, 150));
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                if (dx != 0 || dy != 0) g.drawString(this.text, x + dx, y + dy);
            }
        }
        g.setColor(Color.WHITE);
        g.drawString(this.text, x, y);
    }
}
