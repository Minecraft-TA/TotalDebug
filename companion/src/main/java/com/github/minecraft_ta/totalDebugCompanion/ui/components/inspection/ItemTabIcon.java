package com.github.minecraft_ta.totalDebugCompanion.ui.components.inspection;

import com.github.minecraft_ta.totalDebugCompanion.ui.UiMetrics;

import javax.swing.Icon;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Objects;

/**
 * A tab icon showing a rendered item once it is available and a fallback icon until then. The item is rendered at
 * {@link #size()} and drawn at that size, so its pixels stay exact.
 */
public final class ItemTabIcon implements Icon {
    private final Icon fallback;
    private final int size = UiMetrics.previewPixels(UiMetrics.ROW_ICON_SIZE);
    private volatile BufferedImage image;

    public ItemTabIcon(Icon fallback) {
        this.fallback = Objects.requireNonNull(fallback, "fallback");
    }

    /** The pixel size to render the item at. */
    public int size() {
        return this.size;
    }

    public void setImage(BufferedImage image) {
        this.image = image;
    }

    @Override
    public void paintIcon(Component component, Graphics graphics, int x, int y) {
        BufferedImage current = this.image;
        if (current == null) {
            this.fallback.paintIcon(component, graphics, x + (this.size - this.fallback.getIconWidth()) / 2,
                    y + (this.size - this.fallback.getIconHeight()) / 2);
            return;
        }
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(current, x, y, this.size, this.size, null);
        } finally {
            g.dispose();
        }
    }

    @Override
    public int getIconWidth() {
        return this.size;
    }

    @Override
    public int getIconHeight() {
        return this.size;
    }
}
