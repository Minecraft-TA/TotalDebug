package com.github.minecraft_ta.totalDebugCompanion.resource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

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
    public byte[] read(int maximumBytes) throws IOException {
        long size = Files.size(this.path);
        if (size > maximumBytes) {
            throw new ResourceTooLargeException(displayName(), maximumBytes);
        }
        try (var stream = Files.newInputStream(this.path)) {
            return ContentSources.readBounded(stream, maximumBytes, displayName());
        }
    }

    @Override
    public Optional<byte[]> readAdjacent(String suffix, int maximumBytes) throws IOException {
        Path adjacent = this.path.resolveSibling(this.path.getFileName() + suffix);
        if (!Files.isRegularFile(adjacent)) {
            return Optional.empty();
        }
        return Optional.of(new LocalFileSource(adjacent).read(maximumBytes));
    }

    public Path path() {
        return this.path;
    }
}
