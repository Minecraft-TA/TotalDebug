package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ModFiles;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ModSummary;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.PixelImages;

import javax.imageio.ImageIO;
import javax.swing.CellRendererPane;
import javax.swing.Icon;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Mod logos as square row icons. A banner is much wider than tall and unreadable in a square, so mods with one keep
 * the generic mod icon; their page header still shows the banner.
 */
public final class ModLogoIcons {
    /** The widest (or tallest) logo that still reads as a square icon. */
    static final double MAXIMUM_ASPECT = 1.5;
    private static final int MAX_CACHED = 1_024;
    private static final int MAXIMUM_LOGO_BYTES = 8 * 1024 * 1024;
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Mod logo loader");
        thread.setDaemon(true);
        return thread;
    });
    private static final Map<Key, CompletableFuture<Optional<ContrastLogo>>> CACHE = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, CompletableFuture<Optional<ContrastLogo>>> eldest) {
            return size() > MAX_CACHED;
        }
    };

    /** A logo file inside a mod file. */
    record Source(URI file, String entry) {
    }

    private record Key(List<Source> sources, int size) {
    }

    private ModLogoIcons() {
    }

    /** A {@code size} square icon for a mod: its logo once loaded when it fits a square, otherwise the mod icon. */
    public static Icon icon(ModSummary summary, int size) {
        List<Source> sources = sources(summary);
        return new LogoIcon(sources.isEmpty() ? null : new Key(sources, size), size);
    }

    /** Where a mod's declared logo is; empty for a mod without one. */
    static List<Source> sources(ModSummary summary) {
        if (summary == null || summary.mod() == null || summary.mod().logo().isEmpty()) return List.of();
        return List.of(new Source(summary.mod().file(), summary.mod().logo()));
    }

    /** Reads the first logo that exists, including one in a JAR nested in another; null when there is none. Blocking. */
    static BufferedImage read(List<Source> sources) throws IOException {
        for (Source source : sources) {
            Optional<byte[]> bytes = ModFiles.read(source.file(), source.entry(), MAXIMUM_LOGO_BYTES);
            if (bytes.isPresent()) return ImageIO.read(new ByteArrayInputStream(bytes.get()));
        }
        return null;
    }

    /** The logo fitted into a {@code size} square, or empty when it is a banner. */
    static Optional<BufferedImage> square(BufferedImage logo, int size) {
        int width = logo.getWidth();
        int height = logo.getHeight();
        if (width > height * MAXIMUM_ASPECT || height > width * MAXIMUM_ASPECT) return Optional.empty();
        return Optional.of(PixelImages.fit(logo, size));
    }

    private static CompletableFuture<Optional<ContrastLogo>> logo(Key key) {
        synchronized (CACHE) {
            return CACHE.computeIfAbsent(key, ignored -> CompletableFuture.supplyAsync(() -> {
                try {
                    BufferedImage logo = read(key.sources());
                    return logo == null ? Optional.<ContrastLogo>empty()
                            : square(logo, key.size()).map(square -> new ContrastLogo(square, logo, 0));
                } catch (IOException | RuntimeException unreadable) {
                    return Optional.<ContrastLogo>empty();
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
                CompletableFuture<Optional<ContrastLogo>> logo = logo(this.key);
                Optional<ContrastLogo> loaded = logo.getNow(null);
                if (loaded != null && loaded.isPresent()) {
                    loaded.get().paintIcon(component, graphics, x, y);
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
