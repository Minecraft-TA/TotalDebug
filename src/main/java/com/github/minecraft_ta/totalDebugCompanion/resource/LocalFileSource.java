package com.github.minecraft_ta.totalDebugCompanion.resource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

public final class LocalFileSource implements ContentSource {

    private final Path path;
    private final Path identityPath;

    public LocalFileSource(Path path) {
        this.path = Objects.requireNonNull(path, "path");
        this.identityPath = path.toAbsolutePath().normalize();
    }

    @Override
    public String identity() {
        return this.identityPath.toString();
    }

    @Override
    public String displayName() {
        return this.path.getFileName().toString();
    }

    @Override
    public String tooltip() {
        return this.identityPath.toString();
    }

    @Override
    public long declaredSize() {
        try {
            return Files.size(this.path);
        } catch (IOException exception) {
            return -1;
        }
    }

    @Override
    public byte[] read(int maximumBytes) throws IOException {
        long size = Files.size(this.path);
        if (size > maximumBytes) {
            throw new ResourceTooLargeException(displayName(), maximumBytes);
        }
        try (var stream = Files.newInputStream(this.path)) {
            return ContentSources.readBounded(stream, maximumBytes, displayName());
        }
    }

    public Path path() {
        return this.path;
    }
}
