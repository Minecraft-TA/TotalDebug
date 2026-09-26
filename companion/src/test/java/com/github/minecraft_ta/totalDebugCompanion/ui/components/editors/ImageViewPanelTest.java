package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.itemrender.TextureAnimation;
import com.github.minecraft_ta.totalDebugCompanion.resource.LoadedResource;
import org.junit.jupiter.api.Test;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
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
                assertEquals(5, toolbar.getComponentCount());
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
    void zoomsInWholeStepsFromActualSize() {
        assertEquals(2, ImageViewPanel.nextScale(1, 1));
        assertEquals(8, ImageViewPanel.nextScale(7, 1));
        assertEquals(6, ImageViewPanel.nextScale(7, -1));
        assertEquals(1, ImageViewPanel.nextScale(2, -1));
        assertEquals(0.8, ImageViewPanel.nextScale(1, -1), 1e-9);
        assertEquals(1, ImageViewPanel.nextScale(0.9, 1));
        assertEquals(64, ImageViewPanel.nextScale(64, 1));
    }

    @Test
    void fitEnlargesSmallTexturesByWholeNumbers() {
        assertEquals(30, ImageViewPanel.fitScale(16, 16, 488, 500));
        assertEquals(64, ImageViewPanel.fitScale(16, 16, 4000, 4000));
        assertEquals(0.5, ImageViewPanel.fitScale(1000, 800, 500, 600), 1e-9);
    }

    @Test
    void checkerboardSquaresCoverWholeImagePixels() throws Exception {
        BufferedImage transparent = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        AtomicReference<ImageViewPanel> panelReference = new AtomicReference<>();
        AtomicReference<JComponent> canvasReference = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            ImageViewPanel panel = new ImageViewPanel(new LoadedResource.Image(transparent, 90));
            panel.setSize(600, 600);
            fitNow(panel);
            panelReference.set(panel);
            canvasReference.set((JComponent) findScrollPane(panel).getViewport().getView());
        });

        BufferedImage target = new BufferedImage(600, 600, BufferedImage.TYPE_INT_ARGB);
        SwingUtilities.invokeAndWait(() -> {
            Graphics2D graphics = target.createGraphics();
            canvasReference.get().paint(graphics);
            graphics.dispose();
        });
        try {
            JComponent canvas = canvasReference.get();
            int scale = (canvas.getPreferredSize().width - 32) / 16;
            assertTrue(scale >= 8, "a 16 x 16 texture fits several times larger");
            int left = (canvas.getWidth() - scale * 16) / 2;
            int top = (canvas.getHeight() - scale * 16) / 2;
            for (int pixel = 0; pixel < 16; pixel++) {
                int x = left + pixel * scale;
                int y = top + pixel * scale;
                int first = target.getRGB(x + 1, y + 1);
                assertEquals(first, target.getRGB(x + scale - 2, y + scale - 2), "one square per pixel");
                if (pixel > 0) assertNotEquals(first, target.getRGB(x - 1, y + 1), "neighbours alternate");
            }
        } finally {
            SwingUtilities.invokeAndWait(panelReference.get()::dispose);
        }
    }

    @Test
    void reportsThePixelUnderThePointer() throws Exception {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(3, 7, 0x808A2BE2);
        List<String> statuses = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            ImageViewPanel panel = new ImageViewPanel(new LoadedResource.Image(image, 90), statuses::add);
            try {
                panel.setSize(600, 600);
                fitNow(panel);
                JComponent canvas = (JComponent) findScrollPane(panel).getViewport().getView();
                int scale = (canvas.getPreferredSize().width - 32) / 16;
                int left = (canvas.getWidth() - scale * 16) / 2;
                int top = (canvas.getHeight() - scale * 16) / 2;
                canvas.dispatchEvent(new MouseEvent(canvas, MouseEvent.MOUSE_MOVED, 0, 0,
                        left + 3 * scale + 1, top + 7 * scale + 1, 0, false));
                assertTrue(statuses.getLast().endsWith("|  3, 7  #8A2BE2, alpha 128"), statuses.getLast());
            } finally { panel.dispose(); }
        });
    }

    @Test
    void playsAnimationFramesAndShowsTheWholeSheetOnRequest() throws Exception {
        BufferedImage sheet = new BufferedImage(16, 48, BufferedImage.TYPE_INT_ARGB);
        sheet.setRGB(0, 16, Color.RED.getRGB());
        sheet.setRGB(0, 32, Color.BLUE.getRGB());
        TextureAnimation animation = new TextureAnimation(16, 16, 1, 3,
                List.of(new TextureAnimation.Frame(2, 1), new TextureAnimation.Frame(1, 1), new TextureAnimation.Frame(7, 1)));
        List<String> statuses = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            ImageViewPanel panel = new ImageViewPanel(new LoadedResource.Image(sheet, 90, animation, ""), statuses::add);
            try {
                JPanel toolbar = (JPanel) panel.getComponent(0);
                JSlider frames = findComponent(toolbar, JSlider.class);
                assertEquals(1, frames.getMaximum(), "frames outside the sheet are skipped");
                assertTrue(statuses.getLast().startsWith("16 x 16, 2 frames"), statuses.getLast());
                frames.setValue(1);
                JLabel label = findComponent(toolbar, JLabel.class);
                assertEquals("Frame 2 / 2", label.getText());
                JButton whole = (JButton) toolbar.getComponent(toolbar.getComponentCount() - 1);
                whole.doClick(0);
                assertTrue(statuses.getLast().startsWith("16 x 48  |"), statuses.getLast());
                assertFalse(frames.isEnabled());
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

    /** Lays the panel out and fits the image, as the first layout pass does on screen. */
    private static void fitNow(ImageViewPanel panel) {
        layOut(panel);
        JButton fit = (JButton) ((JPanel) panel.getComponent(0)).getComponent(2);
        fit.doClick(0);
        fit.doClick(0);
        layOut(panel);
    }

    /** Headless components have no peer, so validate() does nothing; lay the tree out directly. */
    private static void layOut(Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof Container nested) layOut(nested);
        }
    }

    private static <T extends Component> T findComponent(Container root, Class<T> type) {
        for (Component component : root.getComponents()) {
            if (type.isInstance(component)) return type.cast(component);
        }
        return null;
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
