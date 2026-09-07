package com.github.minecraft_ta.totaldebug.runtime;

import com.github.minecraft_ta.totaldebug.storage.CacheFiles;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** One runtime's ordered physical sources, shared by javac and the Companion inventory. */
public record PreparedRuntimeSources(String id, Path cacheDirectory, List<Source> sources) {
    public PreparedRuntimeSources {
        Objects.requireNonNull(id, "id");
        cacheDirectory = Objects.requireNonNull(cacheDirectory, "cacheDirectory").toAbsolutePath().normalize();
        sources = List.copyOf(sources);
        if (id.isBlank() || sources.isEmpty()) {
            throw new IllegalArgumentException("Prepared runtime sources require an id and at least one source");
        }
    }

    public <T, E extends Exception> T withCurrentSources(
            CacheFiles.Operation<T, E> operation
    ) throws IOException, E {
        return CacheFiles.locked(this.cacheDirectory.getParent(), () -> {
            CacheFiles.requireIdentity(
                    this.cacheDirectory.resolve("manifest.json"), "id", this.id);
            return operation.run();
        });
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
