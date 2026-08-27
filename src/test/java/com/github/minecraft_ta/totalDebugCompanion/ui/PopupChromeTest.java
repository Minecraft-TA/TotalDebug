package com.github.minecraft_ta.totalDebugCompanion.ui;

import org.junit.jupiter.api.Test;

import java.awt.Dimension;
import java.awt.Point;
import java.awt.Rectangle;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class PopupChromeTest {
    private static final Rectangle SCREEN = new Rectangle(-1920, -1080, 1920, 1080);

    @Test
    void placesPopupBelowTheCompleteSourceLineWhenItFits() {
        assertEquals(
                new Point(-1800, -976),
                PopupChrome.adjacentLocation(
                        new Rectangle(-1800, -1000, 800, 24),
                        new Dimension(500, 200),
                        SCREEN
                )
        );
    }

    @Test
    void placesPopupAboveTheSourceLineWhenItDoesNotFitBelow() {
        assertEquals(
                new Point(-1800, -280),
                PopupChrome.adjacentLocation(
                        new Rectangle(-1800, -80, 800, 24),
                        new Dimension(500, 200),
                        SCREEN
                )
        );
    }

    @Test
    void clampsOversizedCoordinatesToTheOwningMonitor() {
        assertEquals(
                new Point(-500, -1080),
                PopupChrome.clamp(
                        new Point(200, -1500),
                        new Dimension(500, 200),
                        SCREEN
                )
        );
    }

    @Test
    void centersUsingBothMonitorOrigins() {
        assertEquals(
                new Point(-1420, -740),
                PopupChrome.centeredLocation(SCREEN, new Dimension(920, 400))
        );
    }
}
