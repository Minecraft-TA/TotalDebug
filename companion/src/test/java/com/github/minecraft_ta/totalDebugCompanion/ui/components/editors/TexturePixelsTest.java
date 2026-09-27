package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TexturePixelsTest {
    private static final int RED = 0xFFFF0000;
    private static final int BLUE = 0xFF0000FF;
    private static final Rectangle ALL = new Rectangle(4, 4);

    @Test
    void aStrokeIsUndoneAndRedoneWhole() {
        TexturePixels pixels = new TexturePixels(new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB));
        pixels.begin();
        pixels.line(0, 0, 3, 3, RED, ALL);
        assertTrue(pixels.end());
        assertEquals(List.of(RED, RED, RED, RED), diagonal(pixels));

        assertTrue(pixels.undo());
        assertEquals(List.of(0, 0, 0, 0), diagonal(pixels));
        assertTrue(pixels.redo());
        assertEquals(List.of(RED, RED, RED, RED), diagonal(pixels));
        assertFalse(pixels.canRedo());

        pixels.begin();
        pixels.set(0, 0, RED, ALL);
        assertFalse(pixels.end(), "a stroke that changes nothing is not kept");
        assertTrue(pixels.undo());
        assertFalse(pixels.canUndo());
    }

    @Test
    void drawingStaysInsideTheShownFrame() {
        TexturePixels pixels = new TexturePixels(new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB));
        Rectangle topFrame = new Rectangle(0, 0, 4, 2);
        pixels.begin();
        pixels.line(0, 0, 0, 3, RED, topFrame);
        pixels.fill(3, 1, BLUE, topFrame);
        pixels.end();

        assertEquals(RED, pixels.color(0, 1));
        assertEquals(0, pixels.color(0, 2), "the frame below is left alone");
        assertEquals(BLUE, pixels.color(1, 0));
        assertEquals(0, pixels.color(1, 2), "a fill does not flow into the next frame");
    }

    @Test
    void aFillFollowsEdgesOnlyAndNeverWrapsToTheNextRow() {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 4; y++) image.setRGB(1, y, RED);
        TexturePixels pixels = new TexturePixels(image);
        pixels.begin();
        pixels.fill(0, 0, BLUE, ALL);
        pixels.end();

        for (int y = 0; y < 4; y++) {
            assertEquals(BLUE, pixels.color(0, y));
            assertEquals(0, pixels.color(3, y), "the wall of red keeps the fill out, and the last column is not the first");
        }
    }

    @Test
    void thePaletteListsTheMostUsedVisibleColorsFirst() {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, BLUE);
        image.setRGB(1, 0, RED);
        image.setRGB(2, 0, RED);
        assertEquals(List.of(RED, BLUE), new TexturePixels(image).palette(ALL, 16), "transparent pixels are no color");
        assertEquals(List.of(RED), new TexturePixels(image).palette(ALL, 1));
    }

    @Test
    void aTextureIsWrittenAsAPngOfTheSamePixels() throws Exception {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(2, 3, 0x80FF0000);
        BufferedImage read = ImageIO.read(new ByteArrayInputStream(TexturePixels.png(image)));

        assertTrue(TexturePixels.same(image, read), "alpha is kept");
        read.setRGB(0, 0, RED);
        assertFalse(TexturePixels.same(image, read));
        assertFalse(TexturePixels.same(image, new BufferedImage(4, 5, BufferedImage.TYPE_INT_ARGB)));
    }

    private static List<Integer> diagonal(TexturePixels pixels) {
        return List.of(pixels.color(0, 0), pixels.color(1, 1), pixels.color(2, 2), pixels.color(3, 3));
    }
}
