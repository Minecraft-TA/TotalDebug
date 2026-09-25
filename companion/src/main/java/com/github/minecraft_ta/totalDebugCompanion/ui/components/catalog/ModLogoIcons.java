package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ModSummary;

import javax.imageio.ImageIO;
import javax.swing.CellRendererPane;
import javax.swing.Icon;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipFile;

/**
 * Mod logos as square row icons. A banner is much wider than tall and unreadable in a square, so mods with one keep
 * the generic mod icon; their page header still shows the banner.
 */
public final class ModLogoIcons {
    /** The widest (or tallest) logo that still reads as a square icon. */
    static final double MAXIMUM_ASPECT = 1.5;
    private static final int MAX_CACHED = 1_024;
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Mod logo loader");
        thread.setDaemon(true);
        return thread;
    });
    private static final Map<Key, CompletableFuture<Optional<BufferedImage>>> CACHE = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, CompletableFuture<Optional<BufferedImage>>> eldest) {
            return size() > MAX_CACHED;
        }
    };

    private record Key(Path file, String logo, int size) {
    }

    private ModLogoIcons() {
    }

    /** A {@code size} square icon for a mod: its logo once loaded when it fits a square, otherwise the mod icon. */
    public static Icon icon(ModSummary summary, int size) {
        if (summary == null || summary.mod() == null || summary.mod().logo().isEmpty() || summary.files().isEmpty()) {
            return new LogoIcon(null, size);
        }
        return new LogoIcon(new Key(summary.files().getFirst(), summary.mod().logo(), size), size);
    }

    /** Reads a logo from a mod's JAR or folder; null when the file has no such image. */
    static BufferedImage read(Path file, String logo) throws IOException {
        if (Files.isDirectory(file)) {
            Path path = file.resolve(logo);
            return Files.isRegularFile(path) ? ImageIO.read(path.toFile()) : null;
        }
        try (ZipFile zip = new ZipFile(file.toFile())) {
            var entry = zip.getEntry(logo);
            if (entry == null) return null;
            try (InputStream input = zip.getInputStream(entry)) {
                return ImageIO.read(input);
            }
        }
    }

    /**
     * The logo fitted into a {@code size} square, or empty when it is a banner. Small logos are enlarged by whole
     * numbers only, so pixel-art logos stay sharp; larger ones are reduced smoothly.
     */
    static Optional<BufferedImage> square(BufferedImage logo, int size) {
        int width = logo.getWidth();
        int height = logo.getHeight();
        if (width > height * MAXIMUM_ASPECT || height > width * MAXIMUM_ASPECT) return Optional.empty();
        double scale = (double) size / Math.max(width, height);
        int drawnWidth;
        int drawnHeight;
        if (scale >= 1) {
            int factor = (int) Math.floor(scale);
            drawnWidth = width * factor;
            drawnHeight = height * factor;
        } else {
            drawnWidth = Math.max(1, (int) Math.round(width * scale));
            drawnHeight = Math.max(1, (int) Math.round(height * scale));
        }
        BufferedImage square = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = square.createGraphics();
        try {
            int x = (size - drawnWidth) / 2;
            int y = (size - drawnHeight) / 2;
            if (scale >= 1) {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                graphics.drawImage(logo, x, y, drawnWidth, drawnHeight, null);
            } else {
                graphics.drawImage(logo.getScaledInstance(drawnWidth, drawnHeight, Image.SCALE_AREA_AVERAGING), x, y, null);
            }
        } finally {
            graphics.dispose();
        }
        return Optional.of(square);
    }

    private static CompletableFuture<Optional<BufferedImage>> logo(Key key) {
        synchronized (CACHE) {
            return CACHE.computeIfAbsent(key, ignored -> CompletableFuture.supplyAsync(() -> {
                try {
                    BufferedImage logo = read(key.file(), key.logo());
                    return logo == null ? Optional.<BufferedImage>empty() : square(logo, key.size());
                } catch (IOException | RuntimeException unreadable) {
                    return Optional.<BufferedImage>empty();
                }
            }, LOADER));
        }
    }

    private static final class LogoIcon implements Icon {
        private final Key key;
        private final int size;
        private boolean repaintQueued;

        private LogoIcon(Key key, int size) {
            this.key = key;
            this.size = size;
        }

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            if (this.key != null) {
                CompletableFuture<Optional<BufferedImage>> logo = logo(this.key);
                Optional<BufferedImage> loaded = logo.getNow(null);
                if (loaded != null && loaded.isPresent()) {
                    graphics.drawImage(loaded.get(), x, y, null);
                    return;
                }
                if (loaded == null && !this.repaintQueued) {
                    this.repaintQueued = true;
                    Component target = repaintTarget(component);
                    logo.thenRun(() -> SwingUtilities.invokeLater(target::repaint));
                }
            }
            Icons.MOD.paintIcon(component, graphics,
                    x + (this.size - Icons.MOD.getIconWidth()) / 2, y + (this.size - Icons.MOD.getIconHeight()) / 2);
        }

        /** Renderers paint through a cell renderer pane; the list or tree that owns it is what must repaint. */
        private static Component repaintTarget(Component component) {
            Component pane = SwingUtilities.getAncestorOfClass(CellRendererPane.class, component);
            return pane != null && pane.getParent() != null ? pane.getParent() : component;
        }

        @Override
        public int getIconWidth() {
            return this.size;
        }

        @Override
        public int getIconHeight() {
            return this.size;
        }
    }
}
