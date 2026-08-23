package com.github.minecraft_ta.totalDebugCompanion.runtime;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

public final class RuntimeSourceManifest {
    public static final String FORMAT_HEADER = "totaldebug-runtime-sources-v1";

    private RuntimeSourceManifest() {
    }

    public static List<Path> read(Path manifest) throws IOException {
        Objects.requireNonNull(manifest, "manifest");
        if (!Files.isRegularFile(manifest)) {
            throw new IOException("Runtime-source manifest does not exist: " + manifest);
        }

        List<String> lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !FORMAT_HEADER.equals(lines.getFirst())) {
            throw new IOException("Unsupported runtime-source manifest: " + manifest);
        }

        LinkedHashSet<Path> sources = new LinkedHashSet<>();
        for (int lineNumber = 1; lineNumber < lines.size(); lineNumber++) {
            String line = lines.get(lineNumber);
            if (line.isBlank()) {
                throw new IOException("Blank runtime-source entry at line " + (lineNumber + 1));
            }

            Path source;
            try {
                URI sourceUri = URI.create(line);
                if (!"file".equalsIgnoreCase(sourceUri.getScheme())) {
                    throw new IllegalArgumentException("URI does not use the file scheme");
                }
                source = Path.of(sourceUri);
            } catch (IllegalArgumentException exception) {
                throw new IOException("Invalid runtime-source URI at line " + (lineNumber + 1), exception);
            }
            if (source.getFileSystem() != FileSystems.getDefault()) {
                throw new IOException("Runtime source is not on the default filesystem: " + source);
            }
            Path absolute = source.toAbsolutePath().normalize();
            if (!Files.isDirectory(absolute) && !Files.isRegularFile(absolute)) {
                throw new IOException("Runtime source does not exist: " + absolute);
            }
            sources.add(absolute);
        }
        if (sources.isEmpty()) {
            throw new IOException("Runtime-source manifest cannot be empty");
        }
        return List.copyOf(sources);
    }
}
