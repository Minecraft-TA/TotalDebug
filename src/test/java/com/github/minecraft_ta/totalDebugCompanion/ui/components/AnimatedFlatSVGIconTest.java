package com.github.minecraft_ta.totalDebugCompanion.ui.components;

import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnimatedFlatSVGIconTest {
    @Test
    void restartsWhenPaintedAfterBeingStopped() throws Exception {
        AnimatedFlatSVGIcon icon = new AnimatedFlatSVGIcon("icons/process");
        try {
            assertFalse(icon.isRunning());
            paint(icon);
            assertTrue(icon.isRunning());

            icon.stop();
            assertFalse(icon.isRunning());
            paint(icon);
            assertTrue(icon.isRunning());
        } finally {
            icon.stop();
        }
    }

    private static void paint(AnimatedFlatSVGIcon icon) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JLabel component = new JLabel(icon);
            BufferedImage image = new BufferedImage(
                    icon.getIconWidth(),
                    icon.getIconHeight(),
                    BufferedImage.TYPE_INT_ARGB
            );
            Graphics2D graphics = image.createGraphics();
            icon.paintIcon(component, graphics, 0, 0);
            graphics.dispose();
        });
    }
}
