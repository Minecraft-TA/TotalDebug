package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import com.github.minecraft_ta.totalDebugCompanion.Icons;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ModFiles;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ModSummary;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.IconLoader;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.PixelImages;

import javax.imageio.ImageIO;
import javax.swing.Icon;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.List;
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
    private static final int MAXIMUM_LOGO_BYTES = 8 * 1024 * 1024;
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Mod logo loader");
        thread.setDaemon(true);
        return thread;
    });
    /** Shared by every list and tree that shows mods; Swing thread only, like each of them. */
    private static final IconLoader<Key> LOGOS = new IconLoader<>(1_024, 4, ModLogoIcons::load);

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

    private static CompletableFuture<Optional<Icon>> load(Key key) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                BufferedImage logo = read(key.sources());
                return logo == null ? Optional.<Icon>empty()
                        : square(logo, key.size()).<Icon>map(square -> new ContrastLogo(square, logo, 0));
            } catch (IOException | RuntimeException unreadable) {
                return Optional.<Icon>empty();
            }
        }, LOADER);
    }

    /** A mod's logo once it is loaded; the mod icon while it loads and when the mod has no square logo. */
    private record LogoIcon(Key key, int size) implements Icon {
        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Icon logo = this.key == null ? null : LOGOS.icon(this.key, component);
            if (logo != null) {
                logo.paintIcon(component, graphics, x, y);
                return;
            }
            Icons.MOD.paintIcon(component, graphics,
                    x + (this.size - Icons.MOD.getIconWidth()) / 2, y + (this.size - Icons.MOD.getIconHeight()) / 2);
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
