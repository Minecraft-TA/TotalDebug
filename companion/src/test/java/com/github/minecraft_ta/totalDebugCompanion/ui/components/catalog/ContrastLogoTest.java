package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContrastLogoTest {
    private static final Color LIGHT_PAGE = new Color(0xF7F8FA);
    private static final Color DARK_PAGE = new Color(0x2B2D30);

    @Test
    void lightLetteringOnTransparencyGetsAPlateOnlyOnALightPage() {
        ContrastLogo lettering = logo(Color.WHITE, 0.3);

        assertTrue(lettering.needsPlate(LIGHT_PAGE));
        assertFalse(lettering.needsPlate(DARK_PAGE));
    }

    @Test
    void darkLetteringGetsAPlateOnlyOnADarkPage() {
        ContrastLogo lettering = logo(new Color(0x202020), 0.3);

        assertFalse(lettering.needsPlate(LIGHT_PAGE));
        assertTrue(lettering.needsPlate(DARK_PAGE));
    }

    @Test
    void aLogoWithItsOwnBackgroundNeverGetsAPlate() {
        ContrastLogo tile = logo(new Color(0x2ECC71), 1.0);
        ContrastLogo whiteTileWithRoundedCorners = logo(Color.WHITE, 0.8);

        assertFalse(tile.needsPlate(LIGHT_PAGE));
        assertFalse(tile.needsPlate(DARK_PAGE));
        assertFalse(whiteTileWithRoundedCorners.needsPlate(LIGHT_PAGE));
    }

    @Test
    void aColorfulLogoWithTransparentMarginsNeverGetsAPlate() {
        ContrastLogo colorful = logo(new Color(0x2ECC71), 0.6);

        assertFalse(colorful.needsPlate(LIGHT_PAGE));
        assertFalse(colorful.needsPlate(DARK_PAGE));
    }

    /** A logo whose top {@code coverage} share is painted in {@code color}; the rest is transparent. */
    private static ContrastLogo logo(Color color, double coverage) {
        BufferedImage image = new BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, 20, (int) Math.round(20 * coverage));
        graphics.dispose();
        return new ContrastLogo(image, image, 0);
    }
}
