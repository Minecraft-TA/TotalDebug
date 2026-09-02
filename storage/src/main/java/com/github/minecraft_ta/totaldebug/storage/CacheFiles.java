package com.github.minecraft_ta.totaldebug.storage;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentHashMap;

/** Serializes cache reads and replacement, including between Minecraft and Companion. */
public final class CacheFiles {
    private static final ConcurrentHashMap<Path, Object> LOCKS = new ConcurrentHashMap<>();

    private CacheFiles() {
    }

    @FunctionalInterface
    public interface Operation<T, E extends Exception> {
        T run() throws IOException, E;
    }

    /** Operations must not recursively acquire the same directory's lock. */
    public static <T, E extends Exception> T locked(Path directory, Operation<T, E> operation) throws IOException, E {
        Files.createDirectories(directory);
        Path root = directory.toRealPath();
        synchronized (LOCKS.computeIfAbsent(root, ignored -> new Object())) {
            try (var channel = FileChannel.open(root.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var lock = channel.lock()) {
                return operation.run();
            }
        }
    }

    public static void requireIdentity(Path manifest, String key, String expected) throws IOException {
        if (!Files.isRegularFile(manifest) || !expected.equals(JsonFiles.string(JsonFiles.read(manifest), key))) {
            throw new IOException("Runtime cache has changed or is being replaced: " + manifest
                    + ". Reconnect to the current Minecraft runtime.");
        }
    }
}
