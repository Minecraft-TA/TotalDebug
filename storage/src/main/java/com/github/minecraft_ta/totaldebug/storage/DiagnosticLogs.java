package com.github.minecraft_ta.totaldebug.storage;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.UUID;

/** Two 4 MiB files per launch, ten retained inactive launches, with live-launch protection. */
public final class DiagnosticLogs {
    public static final String LOG_PROPERTY = "totaldebug.companionLog";
    private static final long FILE_BYTES = 4 * 1024 * 1024;
    private static final int RETAINED_LAUNCHES = 10;
    private static final String LOG_NAME = "companion.log";
    private static final String LOCK_NAME = ".lock";

    private DiagnosticLogs() { }

    public record Reservation(Path log, FileLease lease) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            lease.close();
        }
    }

    public static synchronized Reservation reserve(AppPaths paths) throws IOException {
        Path root = paths.logs();
        Files.createDirectories(root);
        try (var channel = FileChannel.open(root.resolve(LOCK_NAME), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var ignored = channel.lock()) {
            String name = Instant.now().toString().replace(':', '-') + "-" + UUID.randomUUID();
            Path directory = Files.createDirectory(root.resolve(name));
            Files.createFile(directory.resolve(LOCK_NAME));
            FileLease lease = FileLease.acquire(directory.resolve(LOCK_NAME));
            try {
                prune(root);
                return new Reservation(directory.resolve(LOG_NAME), lease);
            } catch (IOException | RuntimeException exception) {
                lease.close();
                throw exception;
            }
        }
    }

    public static synchronized OutputStream open(AppPaths paths, Path log) throws IOException {
        Path target = log.toAbsolutePath().normalize();
        Path directory = target.getParent();
        if (!target.getFileName().toString().equals(LOG_NAME) || directory == null
                || !paths.logs().equals(directory.getParent()) || !directory.toRealPath().getParent().equals(paths.logs().toRealPath())) {
            throw new IOException("Log target is outside the owned launch directory: " + log);
        }
        try (var channel = FileChannel.open(paths.logs().resolve(LOCK_NAME), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var ignored = channel.lock()) {
            FileLease lease = FileLease.acquire(directory.resolve(LOCK_NAME));
            try {
                return new RotatingOutput(target, lease, FILE_BYTES);
            } catch (IOException exception) {
                lease.close();
                throw exception;
            }
        }
    }

    private static void prune(Path root) throws IOException {
        try (var paths = Files.list(root)) {
            var directories = paths.filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().matches("\\d{4}-\\d{2}-\\d{2}T.*-[a-f0-9-]{36}"))
                    .sorted(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed())
                    .toList();
            for (Path directory : directories.stream().skip(RETAINED_LAUNCHES).toList()) {
                try {
                    try (var channel = FileChannel.open(directory.resolve(LOCK_NAME), StandardOpenOption.READ, StandardOpenOption.WRITE);
                         FileLock lock = channel.tryLock()) {
                        if (lock == null) {
                            continue;
                        }
                    }
                    AtomicFiles.deleteOwned(root, directory);
                } catch (OverlappingFileLockException ignored) {
                    // A launching or running process still owns this log.
                } catch (IOException exception) {
                    System.err.println("Could not reclaim diagnostic log " + directory + ": " + exception.getMessage());
                }
            }
        }
    }

    static final class RotatingOutput extends OutputStream {
        private final Path file;
        private final FileLease lease;
        private final long limit;
        private OutputStream output;
        private long bytes;

        RotatingOutput(Path file, FileLease lease, long limit) throws IOException {
            this.file = file;
            this.lease = lease;
            this.limit = limit;
            this.output = Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            this.bytes = Files.size(file);
        }

        @Override
        public synchronized void write(int value) throws IOException {
            write(new byte[]{(byte) value}, 0, 1);
        }

        @Override
        public synchronized void write(byte[] values, int offset, int length) throws IOException {
            java.util.Objects.checkFromIndexSize(offset, length, values.length);
            while (length > 0) {
                if (this.bytes >= this.limit) {
                    this.output.close();
                    Files.move(this.file, this.file.resolveSibling("previous.log"),
                            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    this.output = Files.newOutputStream(this.file, StandardOpenOption.CREATE_NEW);
                    this.bytes = 0;
                }
                int count = (int) Math.min(length, this.limit - this.bytes);
                this.output.write(values, offset, count);
                this.bytes += count;
                offset += count;
                length -= count;
            }
        }

        @Override
        public synchronized void flush() throws IOException {
            this.output.flush();
        }

        @Override
        public synchronized void close() throws IOException {
            try {
                this.output.close();
            } finally {
                this.lease.close();
            }
        }
    }
}
