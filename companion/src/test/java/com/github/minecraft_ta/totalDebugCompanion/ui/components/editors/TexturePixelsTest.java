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
    void thePaletteListsTheMostUsedVisibleColorsFirstAndFollowsStrokes() {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, BLUE);
        image.setRGB(1, 0, RED);
        image.setRGB(2, 0, RED);
        TexturePixels pixels = new TexturePixels(image);
        assertEquals(List.of(RED, BLUE), pixels.palette(16), "transparent pixels are no color");
        assertEquals(List.of(RED), pixels.palette(1));

        pixels.begin();
        pixels.line(0, 1, 3, 1, BLUE, ALL);
        pixels.set(1, 0, 0, ALL);
        pixels.end();
        assertEquals(List.of(BLUE, RED), pixels.palette(16));
        pixels.undo();
        assertEquals(List.of(RED, BLUE), pixels.palette(16), "an undone stroke takes its colors back");
    }

    @Test
    void nothingIsUndoneWhileAStrokeIsBeingDrawn() {
        TexturePixels pixels = new TexturePixels(new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB));
        pixels.begin();
        pixels.set(0, 0, RED, ALL);
        pixels.end();
        pixels.begin();
        pixels.set(0, 0, BLUE, ALL);
        assertFalse(pixels.undo(), "the stroke being drawn has the pixel's color before it already");
        pixels.end();
        assertTrue(pixels.undo());
        assertEquals(RED, pixels.color(0, 0));
        assertTrue(pixels.undo());
        assertEquals(0, pixels.color(0, 0));
    }

    @Test
    void aLargeFillPaintsEveryPixelOnce() {
        TexturePixels pixels = new TexturePixels(new BufferedImage(1024, 1024, BufferedImage.TYPE_INT_ARGB));
        Rectangle all = new Rectangle(1024, 1024);
        pixels.begin();
        pixels.fill(512, 512, RED, all);
        assertTrue(pixels.end());
        assertEquals(List.of(RED, RED), List.of(pixels.color(0, 0), pixels.color(1023, 1023)));
        assertEquals(List.of(RED), pixels.palette(16));
        assertTrue(pixels.undo());
        assertEquals(0, pixels.color(1023, 0));
    }

    @Test
    void aSheetTooLargeToCountHasNoPalette() {
        TexturePixels pixels = new TexturePixels(new BufferedImage(1025, 1024, BufferedImage.TYPE_INT_ARGB));
        pixels.begin();
        pixels.set(0, 0, RED, new Rectangle(1025, 1024));
        pixels.end();
        assertEquals(List.of(), pixels.palette(16), "counting a large sheet's colors could fill the memory");
    }

    @Test
    void aGrayTextureKeepsItsValuesAsTheGameReadsThem() {
        BufferedImage gray = new BufferedImage(2, 1, BufferedImage.TYPE_BYTE_GRAY);
        gray.getRaster().setSample(0, 0, 0, 128);
        gray.getRaster().setSample(1, 0, 0, 255);
        BufferedImage copy = TexturePixels.copy(gray);

        assertEquals(0xFF808080, copy.getRGB(0, 0), "Java would read 128 as linear light and brighten it");
        assertEquals(0xFFFFFFFF, copy.getRGB(1, 0));
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
