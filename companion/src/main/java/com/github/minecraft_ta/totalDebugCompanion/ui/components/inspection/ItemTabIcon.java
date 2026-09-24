package com.github.minecraft_ta.totalDebugCompanion.ui.components.inspection;

import javax.swing.Icon;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Objects;

/**
 * A tab icon showing a rendered item once it is available and a fallback icon until then. The image is drawn at the
 * fallback's size with nearest-neighbor scaling, which keeps item pixel art sharp.
 */
public final class ItemTabIcon implements Icon {
    private final Icon fallback;
    private volatile BufferedImage image;

    public ItemTabIcon(Icon fallback) {
        this.fallback = Objects.requireNonNull(fallback, "fallback");
    }

    public void setImage(BufferedImage image) {
        this.image = image;
    }

    boolean hasImage() {
        return this.image != null;
    }

    @Override
    public void paintIcon(Component component, Graphics graphics, int x, int y) {
        BufferedImage current = this.image;
        if (current == null) {
            this.fallback.paintIcon(component, graphics, x, y);
            return;
        }
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(current, x, y, getIconWidth(), getIconHeight(), null);
        } finally {
            g.dispose();
        }
    }

    @Override
    public int getIconWidth() {
        return this.fallback.getIconWidth();
    }

    @Override
    public int getIconHeight() {
        return this.fallback.getIconHeight();
    }
}
