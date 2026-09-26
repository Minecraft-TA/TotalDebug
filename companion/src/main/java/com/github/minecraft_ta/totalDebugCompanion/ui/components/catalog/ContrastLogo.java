package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import javax.swing.Icon;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * A mod logo that stays visible in both themes. Lettering on transparency in the page's own tone, such as white
 * lettering in the light theme, is drawn on a plate of the opposite tone; every other logo is drawn as is.
 */
final class ContrastLogo implements Icon {
    /** A logo covering more of its area, such as a tile with rounded corners, brings its own background. */
    private static final double OWN_BACKGROUND = 0.75;
    /** The share of visible pixels in the page's tone from which a logo would largely disappear. */
    private static final double DISAPPEARS = 0.5;

    private final BufferedImage image;
    private final int padding;
    private final double coverage;
    private final double nearWhite;
    private final double nearBlack;

    /**
     * {@code image} is drawn; {@code source} is the logo as the mod ships it, measured once: how much of it is opaque,
     * and which share of its visible pixels is near white or near black. Margins added while fitting do not count.
     */
    ContrastLogo(BufferedImage image, BufferedImage source, int padding) {
        this.image = image;
        this.padding = padding;
        double alpha = 0;
        int visible = 0;
        int white = 0;
        int black = 0;
        int[] pixels = source.getRGB(0, 0, source.getWidth(), source.getHeight(), null, 0, source.getWidth());
        for (int pixel : pixels) {
            int a = pixel >>> 24;
            alpha += a / 255.0;
            if (a < 128) continue;
            visible++;
            double luminance = luminance(pixel);
            if (luminance >= 0.85) white++;
            else if (luminance <= 0.2) black++;
        }
        this.coverage = alpha / pixels.length;
        this.nearWhite = visible == 0 ? 0 : (double) white / visible;
        this.nearBlack = visible == 0 ? 0 : (double) black / visible;
    }

    boolean needsPlate(Color page) {
        double inPageTone = luminance(page.getRGB()) > 0.5 ? this.nearWhite : this.nearBlack;
        return this.coverage < OWN_BACKGROUND && inPageTone >= DISAPPEARS;
    }

    @Override
    public void paintIcon(Component component, Graphics graphics, int x, int y) {
        Color page = UIManager.getColor("Panel.background");
        if (page != null && needsPlate(page)) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(luminance(page.getRGB()) > 0.5 ? new Color(0x2B2D30) : new Color(0xF2F3F5));
                int arc = Math.max(6, Math.min(getIconWidth(), getIconHeight()) / 5);
                g.fillRoundRect(x, y, getIconWidth(), getIconHeight(), arc, arc);
            } finally {
                g.dispose();
            }
        }
        graphics.drawImage(this.image, x + this.padding, y + this.padding, null);
    }

    @Override
    public int getIconWidth() {
        return this.image.getWidth() + 2 * this.padding;
    }

    @Override
    public int getIconHeight() {
        return this.image.getHeight() + 2 * this.padding;
    }

    private static double luminance(int rgb) {
        return (0.2126 * ((rgb >> 16) & 255) + 0.7152 * ((rgb >> 8) & 255) + 0.0722 * (rgb & 255)) / 255.0;
    }
}
