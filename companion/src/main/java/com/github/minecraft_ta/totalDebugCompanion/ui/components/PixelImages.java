package com.github.minecraft_ta.totalDebugCompanion.ui.components;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/** Fits images into previews without blurring pixel art. */
public final class PixelImages {
    private PixelImages() {
    }

    /** The image centered in a {@code size} square, scaled as {@link #fitWithin} scales it. */
    public static BufferedImage fit(BufferedImage image, int size) {
        BufferedImage fitted = fitWithin(image, size, size);
        BufferedImage square = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = square.createGraphics();
        try {
            graphics.drawImage(fitted, (size - fitted.getWidth()) / 2, (size - fitted.getHeight()) / 2, null);
        } finally {
            graphics.dispose();
        }
        return square;
    }

    /**
     * The image scaled to fit {@code maxWidth} by {@code maxHeight}, keeping its proportions. Small images grow by
     * whole numbers only, so every pixel keeps the same size; larger ones shrink smoothly.
     */
    public static BufferedImage fitWithin(BufferedImage image, int maxWidth, int maxHeight) {
        double scale = Math.min((double) maxWidth / image.getWidth(), (double) maxHeight / image.getHeight());
        if (scale >= 1) scale = Math.floor(scale);
        int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
        BufferedImage fitted = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = fitted.createGraphics();
        try {
            if (scale >= 1) {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                graphics.drawImage(image, 0, 0, width, height, null);
            } else {
                graphics.drawImage(image.getScaledInstance(width, height, Image.SCALE_AREA_AVERAGING), 0, 0, null);
            }
        } finally {
            graphics.dispose();
        }
        return fitted;
    }
}
