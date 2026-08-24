package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.resource.LoadedResource;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.global.BottomInformationBar;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.CompanionTheme;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.DynamicMatteBorder;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeManager;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.InputEvent;
import java.awt.image.BufferedImage;
import java.util.function.Consumer;

public final class ImageViewPanel extends JPanel {

    private static final double MINIMUM_SCALE = 0.05;
    private static final double MAXIMUM_SCALE = 32;

    private final BufferedImage image;
    private final int byteCount;
    private final ImageCanvas canvas;
    private final JScrollPane scrollPane;
    private final BottomInformationBar informationBar;
    private final Consumer<CompanionTheme> themeListener = theme -> applyTheme();

    private boolean fitMode = true;
    private boolean disposed;

    public ImageViewPanel(LoadedResource.Image content) {
        this(content, new BottomInformationBar());
    }

    ImageViewPanel(LoadedResource.Image content, BottomInformationBar informationBar) {
        super(new BorderLayout());
        this.informationBar = informationBar;
        this.image = content.value();
        this.byteCount = content.byteCount();
        this.canvas = new ImageCanvas(this.image);
        this.scrollPane = new JScrollPane(this.canvas);
        this.scrollPane.setBorder(BorderFactory.createEmptyBorder());
        // The canvas changes size and recenters itself while zooming or fitting. JViewport's
        // default blit mode can copy pixels from its previous bounds instead of repainting them,
        // leaving duplicate images or unrelated window contents behind after a resize.
        this.scrollPane.getViewport().setScrollMode(JViewport.SIMPLE_SCROLL_MODE);
        this.scrollPane.getViewport().addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent event) {
                if (fitMode) {
                    fitImage();
                }
            }
        });

        add(createToolbar(), BorderLayout.NORTH);
        add(this.scrollPane, BorderLayout.CENTER);
        this.canvas.addMouseWheelListener(event -> {
            if ((event.getModifiersEx() & InputEvent.CTRL_DOWN_MASK) == 0) {
                return;
            }
            event.consume();
            setScale(this.canvas.scale() * Math.pow(1.15, -event.getPreciseWheelRotation()), false);
        });

        ThemeManager.addThemeChangeListener(this.themeListener);
        applyTheme();
        SwingUtilities.invokeLater(this::fitImage);
    }

    private JComponent createToolbar() {
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        toolbar.setBorder(DynamicMatteBorder.rule(0, 0, 1, 0));
        toolbar.add(button("-", "Zoom out", () -> setScale(this.canvas.scale() / 1.25, false)));
        toolbar.add(button("+", "Zoom in", () -> setScale(this.canvas.scale() * 1.25, false)));
        toolbar.add(button("Fit", "Fit image to the available space", this::fitImage));
        toolbar.add(button("100%", "Show the image at its actual size", () -> setScale(1, false)));
        return toolbar;
    }

    private JButton button(String text, String tooltip, Runnable action) {
        JButton button = new JButton(text);
        button.setFocusable(false);
        button.setToolTipText(tooltip);
        button.addActionListener(event -> action.run());
        return button;
    }

    private void fitImage() {
        Dimension extent = this.scrollPane.getViewport().getExtentSize();
        if (extent.width <= 0 || extent.height <= 0) {
            return;
        }
        double widthScale = (extent.width - 32d) / this.image.getWidth();
        double heightScale = (extent.height - 32d) / this.image.getHeight();
        setScale(Math.min(1, Math.min(widthScale, heightScale)), true);
    }

    private void setScale(double scale, boolean fitMode) {
        this.fitMode = fitMode;
        this.canvas.setScale(Math.clamp(scale, MINIMUM_SCALE, MAXIMUM_SCALE));
        updateStatus();
    }

    private void updateStatus() {
        this.informationBar.setDefaultInfoText(
                this.image.getWidth() + " x " + this.image.getHeight()
                        + "  |  PNG  |  " + formatBytes(this.byteCount)
                        + "  |  " + Math.round(this.canvas.scale() * 100) + "%",
                com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors.mutedText()
        );
    }

    private void applyTheme() {
        Color background = ThemeManager.palette().background();
        this.canvas.setBackground(background);
        this.scrollPane.getViewport().setBackground(background);
        updateStatus();
        repaint();
    }

    public void dispose() {
        if (this.disposed) {
            return;
        }
        this.disposed = true;
        ThemeManager.removeThemeChangeListener(this.themeListener);
        this.image.flush();
    }

    public BottomInformationBar getBottomInformationBar() {
        return this.informationBar;
    }

    private static String formatBytes(long bytes) {
        if (bytes >= 1024 * 1024) {
            return "%.1f MiB".formatted(bytes / (1024d * 1024d));
        }
        if (bytes >= 1024) {
            return "%.1f KiB".formatted(bytes / 1024d);
        }
        return bytes + " B";
    }

    private static final class ImageCanvas extends JComponent implements Scrollable {

        private static final int PADDING = 16;
        private static final int CHECKER_SIZE = 8;

        private final BufferedImage image;
        private double scale = 1;

        private ImageCanvas(BufferedImage image) {
            this.image = image;
            setOpaque(true);
        }

        private double scale() {
            return this.scale;
        }

        private void setScale(double scale) {
            this.scale = scale;
            revalidate();
            repaint();
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(
                    (int) Math.ceil(this.image.getWidth() * this.scale) + PADDING * 2,
                    (int) Math.ceil(this.image.getHeight() * this.scale) + PADDING * 2
            );
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            int width = (int) Math.round(this.image.getWidth() * this.scale);
            int height = (int) Math.round(this.image.getHeight() * this.scale);
            int x = Math.max(PADDING, (getWidth() - width) / 2);
            int y = Math.max(PADDING, (getHeight() - height) / 2);

            Graphics2D graphics2D = (Graphics2D) graphics.create();
            // A bare JComponent has no UI delegate, so super.paintComponent() does not clear it.
            // Paint the opaque background explicitly before drawing at the new zoom position.
            graphics2D.setColor(getBackground());
            graphics2D.fillRect(0, 0, getWidth(), getHeight());
            paintCheckerboard(graphics2D, x, y, width, height);
            graphics2D.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    this.scale >= 1
                            ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
                            : RenderingHints.VALUE_INTERPOLATION_BILINEAR
            );
            graphics2D.drawImage(this.image, x, y, width, height, null);
            graphics2D.dispose();
        }

        private static void paintCheckerboard(Graphics2D graphics, int x, int y, int width, int height) {
            Shape oldClip = graphics.getClip();
            graphics.clipRect(x, y, width, height);
            Color light = new Color(0xC8C8C8);
            Color dark = new Color(0x9E9E9E);
            for (int row = 0; row * CHECKER_SIZE < height; row++) {
                for (int column = 0; column * CHECKER_SIZE < width; column++) {
                    graphics.setColor((row + column) % 2 == 0 ? light : dark);
                    graphics.fillRect(
                            x + column * CHECKER_SIZE,
                            y + row * CHECKER_SIZE,
                            CHECKER_SIZE,
                            CHECKER_SIZE
                    );
                }
            }
            graphics.setClip(oldClip);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return orientation == SwingConstants.HORIZONTAL ? visibleRect.width - 16 : visibleRect.height - 16;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return getParent() instanceof JViewport viewport && getPreferredSize().width < viewport.getWidth();
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return getParent() instanceof JViewport viewport && getPreferredSize().height < viewport.getHeight();
        }
    }
}
