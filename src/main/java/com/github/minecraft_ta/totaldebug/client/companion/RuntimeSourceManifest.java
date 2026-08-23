package com.github.minecraft_ta.totaldebug.client.companion;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

final class RuntimeSourceManifest {
    static final String FORMAT_HEADER = "totaldebug-runtime-sources-v1";

    private RuntimeSourceManifest() {
    }

    static void write(Path manifest, Collection<Path> sources) throws IOException {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(sources, "sources");

        List<String> lines = new ArrayList<>();
        lines.add(FORMAT_HEADER);
        for (Path source : normalize(sources, false)) {
            lines.add(source.toUri().toASCIIString());
        }
        if (lines.size() == 1) {
            throw new IOException("Runtime-source manifest cannot be empty");
        }
        Path parent = manifest.getParent();
        if (parent == null) {
            throw new IOException("Runtime-source manifest has no parent directory: " + manifest);
        }
        Files.createDirectories(parent);
        Files.write(manifest, lines, StandardCharsets.UTF_8);
    }

    static List<Path> read(Path manifest) throws IOException {
        Objects.requireNonNull(manifest, "manifest");
        if (!Files.isRegularFile(manifest)) {
            throw new IOException("Runtime-source manifest does not exist: " + manifest);
        }

        List<String> lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !FORMAT_HEADER.equals(lines.getFirst())) {
            throw new IOException("Unsupported runtime-source manifest: " + manifest);
        }

        List<Path> sources = new ArrayList<>();
        for (int lineNumber = 1; lineNumber < lines.size(); lineNumber++) {
            String line = lines.get(lineNumber);
            if (line.isBlank()) {
                throw new IOException("Blank runtime-source entry at line " + (lineNumber + 1));
            }
            try {
                URI sourceUri = URI.create(line);
                if (!"file".equalsIgnoreCase(sourceUri.getScheme())) {
                    throw new IllegalArgumentException("URI does not use the file scheme");
                }
                sources.add(Path.of(sourceUri));
            } catch (IllegalArgumentException exception) {
                throw new IOException("Invalid runtime-source URI at line " + (lineNumber + 1), exception);
            }
        }
        return normalize(sources, true);
    }

    private static List<Path> normalize(Collection<Path> sources, boolean requireExisting) throws IOException {
        LinkedHashSet<Path> normalized = new LinkedHashSet<>();
        for (Path source : sources) {
            Objects.requireNonNull(source, "sources contains null");
            if (source.getFileSystem() != FileSystems.getDefault()) {
                throw new IOException("Runtime source is not on the default filesystem: " + source);
            }
            Path absolute = source.toAbsolutePath().normalize();
            if (requireExisting && !Files.isDirectory(absolute) && !Files.isRegularFile(absolute)) {
                throw new IOException("Runtime source does not exist: " + absolute);
            }
            normalized.add(absolute);
        }
        if (normalized.isEmpty()) {
            throw new IOException("Runtime-source manifest cannot be empty");
        }
        return List.copyOf(normalized);
    }
}
