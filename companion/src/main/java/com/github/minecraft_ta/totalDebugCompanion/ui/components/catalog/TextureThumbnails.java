package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import com.github.minecraft_ta.totalDebugCompanion.util.Strand;
import com.github.minecraft_ta.totalDebugCompanion.util.Workers;
import com.github.minecraft_ta.totalDebugCompanion.catalog.ModResources;
import com.github.minecraft_ta.totalDebugCompanion.itemrender.TextureAnimation;
import com.github.minecraft_ta.totalDebugCompanion.itemrender.TextureImages;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.IconLoader;
import com.github.minecraft_ta.totalDebugCompanion.ui.components.PixelImages;

import javax.swing.Icon;
import javax.swing.ImageIcon;
import java.awt.Component;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipFile;

/**
 * Square previews of a mod's textures; an animation strip shows its first frame. Files are read on one thread, which
 * keeps the mod archives it reads open only while previews are loading, so a mod's JAR can be replaced meanwhile.
 */
final class TextureThumbnails {
    /** Checked before decoding: a small file can declare a size whose pixels would not fit in memory. */
    private static final long MAX_TEXTURE_PIXELS = 16L * 1024 * 1024;
    static final int MAX_TEXTURE_BYTES = 4 * 1024 * 1024;

    private final int size;
    /** Where the previews are read, one at a time, as the archives are open there. */
    private final Strand loader = Workers.fileStrand();
    /** On the loader only; by archive and version, so a replaced archive is opened anew. */
    private final Map<Archive, ZipFile> archives = new HashMap<>();
    /** By resource, whose version tells a texture replaced at the same path apart. */
    private final IconLoader<ModResources.Resource> icons;
    /** Previews requested and not yet finished; the archives close when none remain. */
    private final AtomicInteger pending = new AtomicInteger();

    TextureThumbnails(int size) {
        this.size = size;
        this.icons = new IconLoader<>(2_048, 8, this::load);
    }

    int size() {
        return this.size;
    }

    /** The preview of a PNG texture, or null while it loads or when it cannot be read. */
    Icon icon(ModResources.Resource texture, Component painting) {
        return this.icons.icon(texture, painting);
    }

    private CompletableFuture<Optional<Icon>> load(ModResources.Resource texture) {
        this.pending.incrementAndGet();
        return CompletableFuture.supplyAsync(() -> {
            try {
                BufferedImage image = TextureImages.decode(read(texture), MAX_TEXTURE_PIXELS);
                return image == null ? Optional.<Icon>empty()
                        : Optional.<Icon>of(new ImageIcon(PixelImages.fit(TextureAnimation.firstFrameOfStrip(image), this.size)));
            } catch (IOException | RuntimeException unreadable) {
                return Optional.<Icon>empty();
            }
        }, this.loader).whenCompleteAsync((icon, failure) -> {
            if (this.pending.decrementAndGet() == 0) closeArchives();
        }, this.loader);
    }

    private byte[] read(ModResources.Resource texture) throws IOException {
        if (!texture.archive()) {
            Path file = texture.file().resolve(texture.path());
            if (Files.size(file) > MAX_TEXTURE_BYTES) throw new IOException("Texture too large");
            return Files.readAllBytes(file);
        }
        Archive key = new Archive(texture.file(), texture.version());
        ZipFile archive = this.archives.get(key);
        if (archive == null) {
            archive = new ZipFile(texture.file().toFile());
            this.archives.put(key, archive);
        }
        var entry = archive.getEntry(texture.path());
        if (entry == null || entry.getSize() > MAX_TEXTURE_BYTES) throw new IOException("Texture unavailable");
        try (InputStream input = archive.getInputStream(entry)) {
            return input.readNBytes(MAX_TEXTURE_BYTES);
        }
    }

    /** Loader thread only. */
    private void closeArchives() {
        for (ZipFile archive : this.archives.values()) {
            try {
                archive.close();
            } catch (IOException ignored) {
                // The archive is only read; nothing is lost when closing fails.
            }
        }
        this.archives.clear();
    }

    private record Archive(Path file, ModResources.Version version) {
    }

    void dispose() {
        this.icons.clear();
        this.loader.execute(this::closeArchives);
    }
}
