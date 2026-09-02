package com.github.minecraft_ta.totaldebug.storage;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;

/** Immutable executable copies. Publishers, running processes and pruning share the same lock. */
public final class LaunchCache {
    private static final int RETAINED_BUILDS = 3;
    private static final String JAR = "TotalDebugCompanion.jar";

    private LaunchCache() { }

    public static synchronized FileLease stage(AppPaths paths, Path source) throws IOException {
        String hash = sha256(source);
        Path root = paths.launchCache();
        Files.createDirectories(root);
        try (var channel = FileChannel.open(root.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var ignored = channel.lock()) {
            AtomicFiles.cleanupAbandonedStaging(root);
            Path directory = root.resolve(hash);
            Path jar = directory.resolve(JAR);
            if (!Files.exists(directory)) {
                AtomicFiles.publishDirectory(directory, staged -> {
                    Path copy = staged.resolve(JAR);
                    Files.copy(source, copy, StandardCopyOption.REPLACE_EXISTING);
                    if (!hash.equals(sha256(copy))) {
                        throw new IOException("Companion changed while its launch copy was being staged");
                    }
                });
            }
            if (!Files.isRegularFile(jar) || !hash.equals(sha256(jar))) {
                throw new IOException("Companion launch cache checksum mismatch: " + jar);
            }
            FileLease lease = FileLease.acquire(jar);
            try {
                Files.setLastModifiedTime(directory, FileTime.fromMillis(System.currentTimeMillis()));
                prune(root);
                return lease;
            } catch (IOException | RuntimeException exception) {
                lease.close();
                throw exception;
            }
        }
    }

    /** Called at the executable entry point while the launching process still holds its pin. */
    public static synchronized FileLease pinRunning(AppPaths paths, Path jar) throws IOException {
        Path normalized = jar.toAbsolutePath().normalize();
        Path root = paths.launchCache();
        if (!normalized.getFileName().toString().equals(JAR) || normalized.getParent() == null
                || !root.equals(normalized.getParent().getParent())
                || !normalized.getParent().getFileName().toString().matches("[a-f0-9]{64}")) {
            return null; // A development/classes launch or an executable outside our owned cache.
        }
        try (var channel = FileChannel.open(root.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var ignored = channel.lock()) {
            return FileLease.acquire(normalized);
        }
    }

    private static void prune(Path root) throws IOException {
        try (var children = Files.list(root)) {
            var entries = children.filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().matches("[a-f0-9]{64}"))
                    .sorted(Comparator.comparingLong(LaunchCache::lastUsed).reversed()).toList();
            for (Path entry : entries.stream().skip(RETAINED_BUILDS).toList()) {
                Path jar = entry.resolve(JAR);
                try {
                    // Non-blocking: an active JVM must never wait for cache reclamation.
                    try (var channel = FileChannel.open(jar, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                        try (FileLock lock = channel.tryLock()) {
                            if (lock == null) {
                                continue;
                            }
                        }
                    }
                    // The root lock prevents a new pin between the check and deletion.
                    AtomicFiles.deleteOwned(root, entry);
                } catch (OverlappingFileLockException ignored) {
                    // Another local user holds the executable.
                } catch (IOException exception) {
                    System.err.println("Could not reclaim inactive Companion build " + entry + ": " + exception.getMessage());
                }
            }
        }
    }

    private static long lastUsed(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException exception) {
            return Long.MAX_VALUE; // Unknown ownership/metadata is not a reason to delete.
        }
    }

    public static String sha256(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            for (int read; (read = input.read(buffer)) != -1;) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }


}
