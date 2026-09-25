package com.github.minecraft_ta.totalDebugCompanion.ui.components;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/** Fits images into square previews without blurring pixel art. */
public final class PixelImages {
    private PixelImages() {
    }

    /**
     * The image centered in a {@code size} square. Small images are enlarged by whole numbers only, so every pixel
     * keeps the same size; larger ones are reduced smoothly.
     */
    public static BufferedImage fit(BufferedImage image, int size) {
        int width = image.getWidth();
        int height = image.getHeight();
        double scale = (double) size / Math.max(width, height);
        int drawnWidth;
        int drawnHeight;
        if (scale >= 1) {
            int factor = (int) Math.floor(scale);
            drawnWidth = width * factor;
            drawnHeight = height * factor;
        } else {
            drawnWidth = Math.max(1, (int) Math.round(width * scale));
            drawnHeight = Math.max(1, (int) Math.round(height * scale));
        }
        BufferedImage square = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = square.createGraphics();
        try {
            int x = (size - drawnWidth) / 2;
            int y = (size - drawnHeight) / 2;
            if (scale >= 1) {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                graphics.drawImage(image, x, y, drawnWidth, drawnHeight, null);
            } else {
                graphics.drawImage(image.getScaledInstance(drawnWidth, drawnHeight, Image.SCALE_AREA_AVERAGING), x, y, null);
            }
        } finally {
            graphics.dispose();
        }
        return square;
    }
}
