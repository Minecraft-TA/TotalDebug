package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;

import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.List;

/**
 * A key assignment drawn as keycaps, such as {@code Ctrl} and {@code G}, outlined in the color of a collision when there
 * is one, with a muted text after them. Without keys it shows only the text.
 */
final class KeyCaps extends JComponent {
    private static final int PADDING = 5;
    private static final int GAP = 3;
    private static final int LEFT = 6;

    private List<String> caps = List.of();
    private Color outline;
    private String text = "";
    private Color foreground = Color.BLACK;
    private Color background = Color.WHITE;

    /** {@code outline} is null for the ordinary border; {@code text} follows the caps in muted color. */
    void configure(List<String> caps, Color outline, String text, Font font, Color foreground, Color background) {
        this.caps = List.copyOf(caps);
        this.outline = outline;
        this.text = text;
        this.foreground = foreground;
        this.background = background;
        setFont(font);
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics metrics = getFontMetrics(getFont());
        int width = LEFT;
        for (String cap : this.caps) width += metrics.stringWidth(cap) + 2 * PADDING + GAP;
        if (!this.text.isEmpty()) width += 6 + metrics.stringWidth(this.text);
        return new Dimension(width + LEFT, metrics.getHeight() + 4);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(this.background);
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setFont(getFont());
            FontMetrics metrics = g.getFontMetrics();
            int capHeight = Math.min(getHeight() - 4, metrics.getHeight() + 2);
            int top = (getHeight() - capHeight) / 2;
            int baseline = top + (capHeight - metrics.getHeight()) / 2 + metrics.getAscent();
            int x = LEFT;
            for (String cap : this.caps) {
                int width = metrics.stringWidth(cap) + 2 * PADDING;
                g.setColor(ThemeColors.headerBackground());
                g.fillRoundRect(x, top, width, capHeight, 6, 6);
                g.setColor(this.outline == null ? ThemeColors.border() : this.outline);
                g.drawRoundRect(x, top, width - 1, capHeight - 1, 6, 6);
                g.setColor(this.foreground);
                g.drawString(cap, x + PADDING, baseline);
                x += width + GAP;
            }
            if (!this.text.isEmpty()) {
                g.setColor(ThemeColors.secondaryText());
                g.drawString(this.text, this.caps.isEmpty() ? x : x + 6 - GAP, baseline);
            }
        } finally {
            g.dispose();
        }
    }
}
