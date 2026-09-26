package com.github.minecraft_ta.totalDebugCompanion.itemrender;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;

/** Decodes texture images with a pixel limit checked before any pixels are allocated. */
public final class TextureImages {
    /** The image declares more pixels than the limit allows. */
    public static final class TooLarge extends IOException {
        TooLarge(long pixels, long limit) {
            super("The image has " + pixels + " pixels, more than the limit of " + limit);
        }
    }

    private TextureImages() {
    }

    /**
     * Decodes {@code bytes}, or returns null when the reader finds no image. A compressed file can declare a huge
     * size, so the declared width and height are checked first.
     */
    public static BufferedImage decode(byte[] bytes, long maxPixels) throws IOException {
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("No image reader recognises the data");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input);
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels > maxPixels) throw new TooLarge(pixels, maxPixels);
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        }
    }
}
