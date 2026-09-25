package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModLogoIconsTest {
    @Test
    void bannersDoNotBecomeSquareIcons() {
        assertTrue(ModLogoIcons.square(new BufferedImage(400, 100, BufferedImage.TYPE_INT_ARGB), 32).isEmpty());
        assertTrue(ModLogoIcons.square(new BufferedImage(100, 200, BufferedImage.TYPE_INT_ARGB), 32).isEmpty());
        assertTrue(ModLogoIcons.square(new BufferedImage(150, 100, BufferedImage.TYPE_INT_ARGB), 32).isPresent());
    }

    @Test
    void smallLogosGrowByWholePixels() {
        BufferedImage logo = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        logo.setRGB(0, 0, Color.RED.getRGB());
        logo.setRGB(1, 0, Color.BLUE.getRGB());

        BufferedImage square = ModLogoIcons.square(logo, 32).orElseThrow();

        assertEquals(32, square.getWidth());
        assertEquals(Color.RED.getRGB(), square.getRGB(0, 0));
        assertEquals(Color.RED.getRGB(), square.getRGB(1, 1));
        assertEquals(Color.BLUE.getRGB(), square.getRGB(2, 0));
        assertEquals(Color.BLUE.getRGB(), square.getRGB(3, 1));
    }

    @Test
    void logosBetweenWholeFactorsAreCenteredWithoutStretching() {
        BufferedImage logo = new BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB);
        logo.setRGB(0, 0, Color.RED.getRGB());

        BufferedImage square = ModLogoIcons.square(logo, 32).orElseThrow();

        assertEquals(Color.RED.getRGB(), square.getRGB(6, 6));
        assertEquals(0, square.getRGB(7, 7) >>> 24);
    }
}
