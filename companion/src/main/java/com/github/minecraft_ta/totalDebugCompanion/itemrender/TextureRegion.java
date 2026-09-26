package com.github.minecraft_ta.totalDebugCompanion.itemrender;

import java.awt.image.BufferedImage;
import java.util.Objects;

/** A sprite's exact pixel bounds in its source image, including partial boundary texels. */
public record TextureRegion(BufferedImage image, double x, double y, double width, double height) {

    public TextureRegion {
        Objects.requireNonNull(image, "image");
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(width) || !Double.isFinite(height)
                || x < 0 || y < 0 || width <= 0 || height <= 0
                || x + width > image.getWidth() || y + height > image.getHeight()) {
            throw new IllegalArgumentException("Texture region must have finite positive bounds inside its image");
        }
    }

    public static TextureRegion full(BufferedImage image) {
        return new TextureRegion(image, 0, 0, image.getWidth(), image.getHeight());
    }

    int sample(double u, double v) {
        return sampleLocal(this.x - Math.floor(this.x) + u * this.width,
                this.y - Math.floor(this.y) + v * this.height);
    }

    /** Samples at the centre of the target pixel, as the GPU does, so scaling does not favour the first texels. */
    int sampleScaled(int targetX, int targetY, int targetWidth, int targetHeight) {
        return sampleLocal(this.x - Math.floor(this.x) + (targetX + 0.5) * this.width / targetWidth,
                this.y - Math.floor(this.y) + (targetY + 0.5) * this.height / targetHeight);
    }

    private int sampleLocal(double localX, double localY) {
        // Floor before adding the frame origin, which must not round a UV across a texel boundary.
        int pixelX = (int) Math.floor(this.x) + Math.clamp((int) Math.floor(localX), 0, columns() - 1);
        int pixelY = (int) Math.floor(this.y) + Math.clamp((int) Math.floor(localY), 0, rows() - 1);
        return this.image.getRGB(pixelX, pixelY);
    }

    int columns() {
        return (int) Math.ceil(this.x + this.width) - (int) Math.floor(this.x);
    }

    int rows() {
        return (int) Math.ceil(this.y + this.height) - (int) Math.floor(this.y);
    }

    int pixel(int column, int row) {
        return this.image.getRGB((int) Math.floor(this.x) + column, (int) Math.floor(this.y) + row);
    }

    double u0(int column) {
        return Math.max(0, (Math.floor(this.x) + column - this.x) / this.width);
    }

    double u1(int column) {
        return Math.min(1, (Math.floor(this.x) + column + 1 - this.x) / this.width);
    }

    double v0(int row) {
        return Math.max(0, (Math.floor(this.y) + row - this.y) / this.height);
    }

    double v1(int row) {
        return Math.min(1, (Math.floor(this.y) + row + 1 - this.y) / this.height);
    }
}
