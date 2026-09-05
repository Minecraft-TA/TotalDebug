package com.github.minecraft_ta.totalDebugCompanion.resource;

import org.apache.commons.compress.archivers.zip.ZipFile;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

public final class ArchiveEntrySource implements ContentSource {

    private final Path archivePath;
    private final Path identityArchivePath;
    private final String entryName;
    private final long declaredSize;

    public ArchiveEntrySource(Path archivePath, String entryName, long declaredSize) {
        this.archivePath = Objects.requireNonNull(archivePath, "archivePath");
        this.identityArchivePath = archivePath.toAbsolutePath().normalize();
        this.entryName = Objects.requireNonNull(entryName, "entryName");
        this.declaredSize = declaredSize;
    }

    @Override
    public String identity() {
        return this.identityArchivePath + "!/" + this.entryName;
    }

    @Override
    public String displayName() {
        int separator = this.entryName.lastIndexOf('/');
        return separator == -1 ? this.entryName : this.entryName.substring(separator + 1);
    }

    @Override
    public String tooltip() {
        return identity();
    }

    @Override
    public byte[] read(int maximumBytes) throws IOException {
        if (this.declaredSize > maximumBytes) {
            throw new ResourceTooLargeException(displayName(), maximumBytes);
        }
        try (ZipFile archive = new ZipFile(this.archivePath.toFile())) {
            var entry = archive.getEntry(this.entryName);
            if (entry == null || entry.isDirectory()) {
                throw new FileNotFoundException("Archive entry not found: " + this.entryName);
            }
            try (var stream = archive.getInputStream(entry)) {
                return ContentSources.readBounded(stream, maximumBytes, displayName());
            }
        }
    }

    public Path archivePath() {
        return this.archivePath;
    }

    public String entryName() {
        return this.entryName;
    }
}
