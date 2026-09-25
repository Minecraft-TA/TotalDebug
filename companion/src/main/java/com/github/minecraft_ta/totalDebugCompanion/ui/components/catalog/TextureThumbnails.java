package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import com.github.minecraft_ta.totalDebugCompanion.catalog.ModResources;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.PixelImages;

import javax.imageio.ImageIO;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipFile;

/**
 * Square previews of a mod's textures for lists with thousands of rows. Only painted cells ask for a preview, a few
 * load at a time, and the list is repainted when one finishes. An animation strip shows its first frame. Swing thread
 * only; files are read on one loader thread, which keeps the mod archives it reads open until disposal.
 */
final class TextureThumbnails {
    static final int MAX_CACHED = 2_048;
    static final int MAX_IN_FLIGHT = 8;
    static final int MAX_TEXTURE_BYTES = 4 * 1024 * 1024;

    private final int size;
    private final ExecutorService loader = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Texture thumbnails");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<Path, ZipFile> archives = new HashMap<>();
    private final Map<ModResources.Resource, Optional<Icon>> cache = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<ModResources.Resource, Optional<Icon>> eldest) {
            return size() > MAX_CACHED;
        }
    };
    private final Set<ModResources.Resource> inFlight = new HashSet<>();
    private boolean disposed;

    TextureThumbnails(int size) {
        this.size = size;
    }

    int size() {
        return this.size;
    }

    /** The preview of a PNG texture, or null while it loads or when it cannot be read. */
    Icon icon(ModResources.Resource texture, Component repaint) {
        Optional<Icon> cached = this.cache.get(texture);
        if (cached != null) return cached.orElse(null);
        if (!this.disposed && this.inFlight.size() < MAX_IN_FLIGHT && this.inFlight.add(texture)) {
            CompletableFuture.supplyAsync(() -> load(texture), this.loader).whenComplete((image, failure) ->
                    SwingUtilities.invokeLater(() -> {
                        if (this.disposed) return;
                        this.inFlight.remove(texture);
                        this.cache.put(texture, failure == null ? image.map(ImageIcon::new) : Optional.empty());
                        repaint.repaint();
                    }));
        }
        return null;
    }

    private Optional<BufferedImage> load(ModResources.Resource texture) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(read(texture)));
            if (image == null) return Optional.empty();
            return Optional.of(PixelImages.fit(firstFrame(image), this.size));
        } catch (IOException | RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    /** The top square of a vertical animation strip, or the whole image. */
    static BufferedImage firstFrame(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        return height > width && height % width == 0 ? image.getSubimage(0, 0, width, width) : image;
    }

    private byte[] read(ModResources.Resource texture) throws IOException {
        if (!texture.archive()) {
            Path file = texture.file().resolve(texture.path());
            if (Files.size(file) > MAX_TEXTURE_BYTES) throw new IOException("Texture too large");
            return Files.readAllBytes(file);
        }
        ZipFile archive = this.archives.get(texture.file());
        if (archive == null) {
            archive = new ZipFile(texture.file().toFile());
            this.archives.put(texture.file(), archive);
        }
        var entry = archive.getEntry(texture.path());
        if (entry == null || entry.getSize() > MAX_TEXTURE_BYTES) throw new IOException("Texture unavailable");
        try (InputStream input = archive.getInputStream(entry)) {
            return input.readNBytes(MAX_TEXTURE_BYTES);
        }
    }

    void dispose() {
        this.disposed = true;
        this.cache.clear();
        this.loader.execute(() -> {
            for (ZipFile archive : this.archives.values()) {
                try {
                    archive.close();
                } catch (IOException ignored) {
                    // The archive is only read; nothing is lost when closing fails.
                }
            }
            this.archives.clear();
        });
        this.loader.shutdown();
    }
}
