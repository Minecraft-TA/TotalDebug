package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.resource.LoadedResource;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ImageViewPanelTest {
    @Test
    void toolbarTracksFitModeAndDisablesZoomAtItsLimits() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ImageViewPanel panel = new ImageViewPanel(new LoadedResource.Image(
                    new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB), 201));
            try {
                JPanel toolbar = (JPanel) panel.getComponent(0);
                JButton out = (JButton) toolbar.getComponent(0);
                JButton in = (JButton) toolbar.getComponent(1);
                JButton fit = (JButton) toolbar.getComponent(2);
                JButton actual = (JButton) toolbar.getComponent(3);
                for (var component : toolbar.getComponents()) {
                    JButton button = (JButton) component;
                    assertTrue(button.isFocusable());
                    assertFalse(button.isRequestFocusEnabled());
                    assertNotNull(button.getIcon());
                    assertNotNull(button.getToolTipText());
                }
                assertTrue(fit.isSelected());
                actual.doClick(0);
                assertFalse(fit.isSelected());
                for (int i = 0; i < 40; i++) out.doClick(0);
                assertFalse(out.isEnabled());
                assertTrue(in.isEnabled());
                for (int i = 0; i < 40; i++) in.doClick(0);
                assertFalse(in.isEnabled());
                assertTrue(out.isEnabled());
                actual.doClick(0);
                assertTrue(in.isEnabled());
                assertTrue(out.isEnabled());
            } finally { panel.dispose(); }
        });
    }


    @Test
    void viewportUsesFullRepaintsForTheDynamicallySizedCanvas() throws Exception {
        AtomicReference<ImageViewPanel> panelReference = new AtomicReference<>();
        AtomicReference<JScrollPane> scrollPaneReference = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            ImageViewPanel panel = new ImageViewPanel(new LoadedResource.Image(
                    new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB),
                    201
            ));
            panelReference.set(panel);
            scrollPaneReference.set(findScrollPane(panel));
        });

        try {
            assertEquals(
                    JViewport.SIMPLE_SCROLL_MODE,
                    scrollPaneReference.get().getViewport().getScrollMode()
            );
        } finally {
            SwingUtilities.invokeAndWait(panelReference.get()::dispose);
        }
    }

    @Test
    void canvasClearsPixelsLeftBehindByAnEarlierImagePosition() throws Exception {
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D imageGraphics = image.createGraphics();
        imageGraphics.setColor(Color.MAGENTA);
        imageGraphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        imageGraphics.dispose();

        AtomicReference<ImageViewPanel> panelReference = new AtomicReference<>();
        AtomicReference<Component> canvasReference = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            ImageViewPanel panel = new ImageViewPanel(new LoadedResource.Image(image, 201));
            Component canvas = findScrollPane(panel).getViewport().getView();
            canvas.setBackground(Color.BLACK);
            panelReference.set(panel);
            canvasReference.set(canvas);
        });

        BufferedImage target = new BufferedImage(200, 200, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = target.createGraphics();
        graphics.setColor(Color.BLACK);
        graphics.fillRect(0, 0, target.getWidth(), target.getHeight());
        SwingUtilities.invokeAndWait(() -> {
            Component canvas = canvasReference.get();
            canvas.setSize(200, 100);
            canvas.paint(graphics);
            canvas.setSize(200, 200);
            canvas.paint(graphics);
        });
        graphics.dispose();

        try {
            assertEquals(Color.BLACK.getRGB(), target.getRGB(100, 50));
            assertEquals(Color.MAGENTA.getRGB(), target.getRGB(100, 100));
        } finally {
            SwingUtilities.invokeAndWait(panelReference.get()::dispose);
        }
    }

    private static JScrollPane findScrollPane(Container root) {
        for (Component component : root.getComponents()) {
            if (component instanceof JScrollPane scrollPane) {
                return scrollPane;
            }
            if (component instanceof Container child) {
                JScrollPane match = findScrollPane(child);
                if (match != null) {
                    return match;
                }
            }
        }
        return null;
    }
}
