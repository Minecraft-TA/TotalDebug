package com.github.minecraft_ta.totaldebug.runtime;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** One runtime's ordered physical sources, shared by javac and the Companion inventory. */
public record PreparedRuntimeSources(String id, List<Source> sources) {
    public PreparedRuntimeSources {
        Objects.requireNonNull(id, "id");
        sources = List.copyOf(sources);
        if (id.isBlank() || sources.isEmpty()) {
            throw new IllegalArgumentException("Prepared runtime sources require an id and at least one source");
        }
    }

    public List<Path> paths() {
        return this.sources.stream().map(Source::path).toList();
    }

    public record Source(RuntimeSourceInventory.Source original, Path path) {
        public Source {
            Objects.requireNonNull(original, "original");
            path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
            if (path.getFileSystem() != FileSystems.getDefault() || !Files.exists(path)) {
                throw new IllegalArgumentException("Prepared runtime source must be a physical file or directory: " + path);
            }
        }

        public String logicalUri() {
            return this.original.path().toUri().toASCIIString();
        }
    }
}
