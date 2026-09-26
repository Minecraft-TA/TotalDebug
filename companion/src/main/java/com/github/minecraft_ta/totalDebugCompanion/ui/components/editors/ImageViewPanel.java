package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.itemrender.TextureAnimation;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.FlatIconButton;
import com.github.minecraft_ta.totalDebugCompanion.resource.LoadedResource;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.CompanionTheme;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.DynamicMatteBorder;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeColors;
import com.github.minecraft_ta.totalDebugCompanion.ui.theme.ThemeManager;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.InputEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

public final class ImageViewPanel extends JPanel {

    private static final double MINIMUM_SCALE = 0.05;
    private static final double MAXIMUM_SCALE = 64;
    /** Zoom levels from 100% up. Whole numbers draw every image pixel with the same number of screen pixels. */
    private static final int[] ZOOM_LEVELS = {1, 2, 3, 4, 5, 6, 8, 10, 12, 16, 20, 24, 32, 40, 48, 64};
    private static final double ZOOM_STEP_BELOW_ACTUAL = 1.25;
    private static final int GAME_TICK_MILLIS = 50;

    private final BufferedImage image;
    private final int byteCount;
    private final String animationProblem;
    /** Played frames whose index lies inside the sheet, in playing order. */
    private final List<TextureAnimation.Frame> frames;
    private final TextureAnimation animation;
    private final ImageCanvas canvas;
    private final JScrollPane scrollPane;
    private final Consumer<String> metadata;
    private final Consumer<CompanionTheme> themeListener = theme -> applyTheme();
    private final FlatIconButton zoomOut = new FlatIconButton(Icons.ZOOM_OUT, false);
    private final FlatIconButton zoomIn = new FlatIconButton(Icons.ZOOM_IN, false);
    private final FlatIconButton fit = new FlatIconButton(Icons.FIT_CONTENT, true);
    private final FlatIconButton pixelGrid = new FlatIconButton(Icons.PIXEL_GRID, true);
    private final FlatIconButton play = new FlatIconButton(Icons.PAUSE, true);
    private final FlatIconButton wholeSheet = new FlatIconButton(Icons.IMAGE_FILE, true);
    private final JSlider frameSlider = new JSlider();
    private final JLabel frameLabel = new JLabel();
    private final Timer animationTimer = new Timer(GAME_TICK_MILLIS, event -> advanceAnimation());

    private boolean fitMode = true;
    private boolean disposed;
    private int framePosition;
    private int frameTicks;
    private double wheelRotation;
    private Point hoveredPixel;

    public ImageViewPanel(LoadedResource.Image content) {
        this(content, ignored -> {});
    }

    ImageViewPanel(LoadedResource.Image content, Consumer<String> metadata) {
        super(new BorderLayout());
        this.metadata = metadata;
        this.image = content.value();
        this.byteCount = content.byteCount();
        this.animationProblem = content.animationProblem();
        this.animation = content.animation();
        this.frames = this.animation == null ? List.of() : this.animation.frames().stream()
                .filter(frame -> this.animation.contains(frame.index()))
                .toList();
        this.canvas = new ImageCanvas(isAnimated() ? frameImage(0) : this.image);
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
        installMouseHandling();

        ThemeManager.addThemeChangeListener(this.themeListener);
        applyTheme();
        if (isAnimated()) {
            this.play.setSelected(true);
            this.animationTimer.start();
        }
        SwingUtilities.invokeLater(this::fitImage);
    }

    private JComponent createToolbar() {
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 2));
        toolbar.setBorder(DynamicMatteBorder.rule(0, 0, 1, 0));
        toolbar.add(button(this.zoomOut, "Zoom out", () -> zoomAtCenter(nextScale(this.canvas.scale(), -1))));
        toolbar.add(button(this.zoomIn, "Zoom in", () -> zoomAtCenter(nextScale(this.canvas.scale(), 1))));
        this.fit.setSelected(this.fitMode);
        toolbar.add(button(this.fit, "Fit image to the available space", () -> {
            if (this.fit.isSelected()) fitImage();
            else setScale(this.canvas.scale(), false);
        }));
        toolbar.add(button(new FlatIconButton(Icons.ACTUAL_ZOOM, false), "Actual size (100%)", () -> zoomAtCenter(1)));
        toolbar.add(button(this.pixelGrid, "Pixel grid from 400%", this.canvas::repaint));
        if (isAnimated()) {
            toolbar.add(Box.createHorizontalStrut(10));
            toolbar.add(button(this.play, "Pause animation", () -> setPlaying(this.play.isSelected())));
            this.frameSlider.setModel(new DefaultBoundedRangeModel(0, 0, 0, this.frames.size() - 1));
            this.frameSlider.setPreferredSize(new Dimension(
                    Math.min(240, 40 + this.frames.size() * 8), this.frameSlider.getPreferredSize().height));
            this.frameSlider.getAccessibleContext().setAccessibleName("Animation frame");
            this.frameSlider.addChangeListener(event -> {
                if (this.frameSlider.getValue() != this.framePosition) {
                    if (this.frameSlider.getValueIsAdjusting()) setPlaying(false);
                    showFrame(this.frameSlider.getValue());
                }
            });
            toolbar.add(this.frameSlider);
            this.frameLabel.setForeground(ThemeColors.secondaryText());
            toolbar.add(this.frameLabel);
            toolbar.add(Box.createHorizontalStrut(6));
            toolbar.add(button(this.wholeSheet, "Show the whole texture sheet", this::updateSheetMode));
            updateFrameLabel();
        }
        return toolbar;
    }

    private JButton button(JButton button, String tooltip, Runnable action) {
        button.setToolTipText(tooltip);
        button.getAccessibleContext().setAccessibleName(tooltip);
        button.addActionListener(event -> action.run());
        return button;
    }

    private void installMouseHandling() {
        MouseAdapter mouse = new MouseAdapter() {
            private Point dragStart;
            private Point viewStart;

            @Override
            public void mousePressed(MouseEvent event) {
                if (!SwingUtilities.isLeftMouseButton(event) && !SwingUtilities.isMiddleMouseButton(event)) return;
                this.dragStart = event.getLocationOnScreen();
                this.viewStart = scrollPane.getViewport().getViewPosition();
                if (canPan()) canvas.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                if (this.dragStart == null) return;
                Point now = event.getLocationOnScreen();
                setViewPosition(this.viewStart.x - (now.x - this.dragStart.x), this.viewStart.y - (now.y - this.dragStart.y));
                hover(event.getPoint());
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                this.dragStart = null;
                canvas.setCursor(Cursor.getDefaultCursor());
            }

            @Override
            public void mouseMoved(MouseEvent event) {
                hover(event.getPoint());
            }

            @Override
            public void mouseExited(MouseEvent event) {
                hover(null);
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent event) {
                if ((event.getModifiersEx() & InputEvent.CTRL_DOWN_MASK) == 0) {
                    // A wheel listener on the canvas keeps the event from its scroll pane; hand ordinary scrolling back.
                    scrollPane.dispatchEvent(SwingUtilities.convertMouseEvent(canvas, event, scrollPane));
                    return;
                }
                event.consume();
                wheelRotation -= event.getPreciseWheelRotation();
                int steps = (int) wheelRotation;
                if (steps == 0) return;
                wheelRotation -= steps;
                double scale = canvas.scale();
                for (int step = 0; step < Math.abs(steps); step++) scale = nextScale(scale, Integer.signum(steps));
                zoomAt(scale, event.getPoint());
            }
        };
        this.canvas.addMouseListener(mouse);
        this.canvas.addMouseMotionListener(mouse);
        this.canvas.addMouseWheelListener(mouse);
    }

    private boolean canPan() {
        Dimension extent = this.scrollPane.getViewport().getExtentSize();
        return this.canvas.getWidth() > extent.width || this.canvas.getHeight() > extent.height;
    }

    private void hover(Point canvasPoint) {
        Point pixel = canvasPoint == null ? null : this.canvas.pixelAt(canvasPoint);
        if (Objects.equals(pixel, this.hoveredPixel)) return;
        this.hoveredPixel = pixel;
        updateStatus();
    }

    /** The next zoom level in {@code direction}: whole-number levels from 100% up, 1.25 steps below. */
    static double nextScale(double scale, int direction) {
        if (direction > 0) {
            if (scale < 1) return Math.min(1, scale * ZOOM_STEP_BELOW_ACTUAL);
            for (int level : ZOOM_LEVELS) if (level > scale + 1e-9) return level;
            return MAXIMUM_SCALE;
        }
        if (scale <= 1 + 1e-9) return Math.max(MINIMUM_SCALE, scale / ZOOM_STEP_BELOW_ACTUAL);
        for (int index = ZOOM_LEVELS.length - 1; index >= 0; index--) {
            if (ZOOM_LEVELS[index] < scale - 1e-9) return ZOOM_LEVELS[index];
        }
        return 1;
    }

    /** The largest scale that shows the whole image, rounded down to a whole number when it enlarges the image. */
    static double fitScale(int imageWidth, int imageHeight, int availableWidth, int availableHeight) {
        double scale = Math.min((double) availableWidth / imageWidth, (double) availableHeight / imageHeight);
        return scale >= 1 ? Math.floor(Math.min(scale, MAXIMUM_SCALE)) : Math.max(MINIMUM_SCALE, scale);
    }

    private void fitImage() {
        Dimension extent = this.scrollPane.getViewport().getExtentSize();
        if (extent.width <= 0 || extent.height <= 0) {
            return;
        }
        BufferedImage shown = this.canvas.image();
        setScale(fitScale(shown.getWidth(), shown.getHeight(),
                extent.width - ImageCanvas.PADDING * 2, extent.height - ImageCanvas.PADDING * 2), true);
    }

    private void zoomAtCenter(double scale) {
        Rectangle visible = this.canvas.getVisibleRect();
        zoomAt(scale, new Point(visible.x + visible.width / 2, visible.y + visible.height / 2));
    }

    /** Changes the zoom while the image point under {@code anchor} stays where it is on screen. */
    private void zoomAt(double scale, Point anchor) {
        JViewport viewport = this.scrollPane.getViewport();
        Point view = viewport.getViewPosition();
        Point2D imagePoint = this.canvas.toImage(anchor);
        setScale(scale, false);
        this.scrollPane.validate();
        Point2D moved = this.canvas.toCanvas(imagePoint);
        setViewPosition((int) Math.round(moved.getX()) - (anchor.x - view.x),
                (int) Math.round(moved.getY()) - (anchor.y - view.y));
    }

    private void setViewPosition(int x, int y) {
        JViewport viewport = this.scrollPane.getViewport();
        Dimension extent = viewport.getExtentSize();
        viewport.setViewPosition(new Point(
                Math.clamp(x, 0, Math.max(0, this.canvas.getWidth() - extent.width)),
                Math.clamp(y, 0, Math.max(0, this.canvas.getHeight() - extent.height))));
    }

    private void setScale(double scale, boolean fitMode) {
        this.fitMode = fitMode;
        this.canvas.setScale(Math.clamp(scale, MINIMUM_SCALE, MAXIMUM_SCALE));
        this.fit.setSelected(fitMode);
        this.zoomOut.setEnabled(this.canvas.scale() > MINIMUM_SCALE);
        this.zoomIn.setEnabled(this.canvas.scale() < MAXIMUM_SCALE);
        updateStatus();
    }

    private boolean isAnimated() {
        return !this.frames.isEmpty();
    }

    private BufferedImage frameImage(int position) {
        Rectangle region = this.animation.region(this.frames.get(position).index());
        return this.image.getSubimage(region.x, region.y, region.width, region.height);
    }

    private void setPlaying(boolean playing) {
        this.play.setSelected(playing);
        this.play.setIcon(playing ? Icons.PAUSE : Icons.RUN);
        String tooltip = playing ? "Pause animation" : "Play animation";
        this.play.setToolTipText(tooltip);
        this.play.getAccessibleContext().setAccessibleName(tooltip);
        if (playing && !this.wholeSheet.isSelected()) this.animationTimer.start();
        else this.animationTimer.stop();
    }

    private void advanceAnimation() {
        if (!isShowing()) return;
        this.frameTicks++;
        if (this.frameTicks < this.frames.get(this.framePosition).ticks()) return;
        showFrame((this.framePosition + 1) % this.frames.size());
    }

    private void showFrame(int position) {
        this.framePosition = position;
        this.frameTicks = 0;
        if (this.frameSlider.getValue() != position) this.frameSlider.setValue(position);
        if (!this.wholeSheet.isSelected()) this.canvas.setImage(frameImage(position));
        updateFrameLabel();
        updateStatus();
    }

    private void updateSheetMode() {
        boolean sheet = this.wholeSheet.isSelected();
        this.canvas.setImage(sheet ? this.image : frameImage(this.framePosition));
        this.frameSlider.setEnabled(!sheet);
        this.play.setEnabled(!sheet);
        setPlaying(this.play.isSelected());
        if (this.fitMode) fitImage();
        updateStatus();
    }

    private void updateFrameLabel() {
        TextureAnimation.Frame frame = this.frames.get(this.framePosition);
        this.frameLabel.setText("Frame " + (this.framePosition + 1) + " / " + this.frames.size());
        this.frameLabel.setToolTipText("Sheet index " + frame.index() + ", " + frame.ticks()
                + (frame.ticks() == 1 ? " tick" : " ticks"));
    }

    private void updateStatus() {
        BufferedImage shown = this.canvas.image();
        StringBuilder status = new StringBuilder()
                .append(shown.getWidth()).append(" x ").append(shown.getHeight());
        if (isAnimated() && !this.wholeSheet.isSelected()) {
            status.append(", ").append(this.frames.size()).append(" frames");
        }
        status.append("  |  PNG  |  ").append(formatBytes(this.byteCount))
                .append("  |  ").append(Math.round(this.canvas.scale() * 100)).append('%');
        Point pixel = this.hoveredPixel;
        if (pixel != null && pixel.x < shown.getWidth() && pixel.y < shown.getHeight()) {
            int argb = shown.getRGB(pixel.x, pixel.y);
            status.append("  |  ").append(pixel.x).append(", ").append(pixel.y)
                    .append("  #").append(String.format(Locale.ROOT, "%06X", argb & 0xFFFFFF))
                    .append(", alpha ").append(argb >>> 24);
        }
        if (!this.animationProblem.isEmpty()) {
            status.append("  |  ").append(this.animationProblem);
        }
        this.metadata.accept(status.toString());
    }

    private void applyTheme() {
        Color background = ThemeManager.palette().background();
        this.canvas.setBackground(background);
        this.scrollPane.getViewport().setBackground(background);
        this.canvas.setDark(ThemeManager.current().dark());
        this.frameLabel.setForeground(ThemeColors.secondaryText());
        updateStatus();
        repaint();
    }

    public void dispose() {
        if (this.disposed) {
            return;
        }
        this.disposed = true;
        this.animationTimer.stop();
        ThemeManager.removeThemeChangeListener(this.themeListener);
        this.image.flush();
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

    private final class ImageCanvas extends JComponent implements Scrollable {

        private static final int PADDING = 16;
        /** The smallest checkerboard square on screen; squares always cover whole image pixels from 100% up. */
        private static final int CHECKER_SIZE = 8;
        private static final double GRID_MINIMUM_SCALE = 4;

        private BufferedImage image;
        private double scale = 1;
        private boolean dark;

        private ImageCanvas(BufferedImage image) {
            this.image = image;
            setOpaque(true);
        }

        private BufferedImage image() {
            return this.image;
        }

        private void setImage(BufferedImage image) {
            boolean resized = image.getWidth() != this.image.getWidth() || image.getHeight() != this.image.getHeight();
            this.image = image;
            if (resized) revalidate();
            repaint();
        }

        private double scale() {
            return this.scale;
        }

        private void setScale(double scale) {
            this.scale = scale;
            revalidate();
            repaint();
        }

        private void setDark(boolean dark) {
            this.dark = dark;
        }

        private int drawnWidth() {
            return (int) Math.round(this.image.getWidth() * this.scale);
        }

        private int drawnHeight() {
            return (int) Math.round(this.image.getHeight() * this.scale);
        }

        private Point origin() {
            return new Point(Math.max(PADDING, (getWidth() - drawnWidth()) / 2),
                    Math.max(PADDING, (getHeight() - drawnHeight()) / 2));
        }

        private Point2D toImage(Point canvasPoint) {
            Point origin = origin();
            return new Point2D.Double((canvasPoint.x - origin.x) / this.scale, (canvasPoint.y - origin.y) / this.scale);
        }

        private Point2D toCanvas(Point2D imagePoint) {
            Point origin = origin();
            return new Point2D.Double(origin.x + imagePoint.getX() * this.scale, origin.y + imagePoint.getY() * this.scale);
        }

        /** The image pixel under a canvas point, or null outside the image. */
        private Point pixelAt(Point canvasPoint) {
            Point2D point = toImage(canvasPoint);
            int x = (int) Math.floor(point.getX());
            int y = (int) Math.floor(point.getY());
            return x < 0 || y < 0 || x >= this.image.getWidth() || y >= this.image.getHeight() ? null : new Point(x, y);
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(drawnWidth() + PADDING * 2, drawnHeight() + PADDING * 2);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            int width = drawnWidth();
            int height = drawnHeight();
            Point origin = origin();

            Graphics2D graphics2D = (Graphics2D) graphics.create();
            // A bare JComponent has no UI delegate, so super.paintComponent() does not clear it.
            // Paint the opaque background explicitly before drawing at the new zoom position.
            graphics2D.setColor(getBackground());
            graphics2D.fillRect(0, 0, getWidth(), getHeight());
            graphics2D.clipRect(origin.x, origin.y, width, height);
            paintCheckerboard(graphics2D, origin);
            graphics2D.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    this.scale >= 1
                            ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
                            : RenderingHints.VALUE_INTERPOLATION_BILINEAR
            );
            graphics2D.drawImage(this.image, origin.x, origin.y, width, height, null);
            if (pixelGrid.isSelected() && this.scale >= GRID_MINIMUM_SCALE) {
                paintPixelGrid(graphics2D, origin);
            }
            graphics2D.dispose();
        }

        /** Squares of whole image pixels at least {@link #CHECKER_SIZE} wide, so they line up with the pixels. */
        private void paintCheckerboard(Graphics2D graphics, Point origin) {
            graphics.setColor(this.dark ? new Color(0x4A4D52) : new Color(0xF2F2F2));
            graphics.fillRect(origin.x, origin.y, drawnWidth(), drawnHeight());
            graphics.setColor(this.dark ? new Color(0x3A3C40) : new Color(0xD4D4D4));
            double cell = this.scale >= 1
                    ? Math.ceil(CHECKER_SIZE / this.scale) * this.scale
                    : CHECKER_SIZE;
            Rectangle clip = graphics.getClipBounds();
            int firstColumn = (int) Math.floor((clip.x - origin.x) / cell);
            int lastColumn = (int) Math.floor((clip.x + clip.width - origin.x) / cell);
            int firstRow = (int) Math.floor((clip.y - origin.y) / cell);
            int lastRow = (int) Math.floor((clip.y + clip.height - origin.y) / cell);
            for (int row = Math.max(0, firstRow); row <= lastRow; row++) {
                int top = origin.y + (int) Math.round(row * cell);
                int bottom = origin.y + (int) Math.round((row + 1) * cell);
                for (int column = Math.max(0, firstColumn); column <= lastColumn; column++) {
                    if ((row + column) % 2 == 0) continue;
                    int left = origin.x + (int) Math.round(column * cell);
                    int right = origin.x + (int) Math.round((column + 1) * cell);
                    graphics.fillRect(left, top, right - left, bottom - top);
                }
            }
        }

        private void paintPixelGrid(Graphics2D graphics, Point origin) {
            graphics.setColor(this.dark ? new Color(255, 255, 255, 36) : new Color(0, 0, 0, 36));
            Rectangle clip = graphics.getClipBounds();
            int height = drawnHeight();
            int width = drawnWidth();
            int firstColumn = Math.max(1, (int) Math.floor((clip.x - origin.x) / this.scale));
            int lastColumn = Math.min(this.image.getWidth() - 1, (int) Math.ceil((clip.x + clip.width - origin.x) / this.scale));
            for (int column = firstColumn; column <= lastColumn; column++) {
                graphics.fillRect(origin.x + (int) Math.round(column * this.scale), origin.y, 1, height);
            }
            int firstRow = Math.max(1, (int) Math.floor((clip.y - origin.y) / this.scale));
            int lastRow = Math.min(this.image.getHeight() - 1, (int) Math.ceil((clip.y + clip.height - origin.y) / this.scale));
            for (int row = firstRow; row <= lastRow; row++) {
                graphics.fillRect(origin.x, origin.y + (int) Math.round(row * this.scale), width, 1);
            }
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
