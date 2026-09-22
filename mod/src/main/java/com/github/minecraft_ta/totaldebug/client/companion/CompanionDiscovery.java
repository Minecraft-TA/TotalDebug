package com.github.minecraft_ta.totaldebug.client.companion;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.storage.CompanionLaunchContract;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.NoSuchFileException;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Watches one application directory. Transport and handshake ownership remain in CompanionAppClient. */
final class CompanionDiscovery implements AutoCloseable {
    enum Result { IDLE, CONNECTED, RETRY, REJECTED }
    private record PublishedFile(FileTime modified, Object key, String content) {
        static PublishedFile read(Path path) throws IOException {
            try {
                var attributes = Files.readAttributes(path, BasicFileAttributes.class);
                return new PublishedFile(attributes.lastModifiedTime(), attributes.fileKey(), Files.readString(path));
            } catch (NoSuchFileException missing) { return null; }
        }
    }
    private record Publication(PublishedFile descriptor, PublishedFile key) { }

    private final Path directory;
    private final Supplier<Result> attempt;
    private final BooleanSupplier connected;
    private final BooleanSupplier enabled;
    private final Thread worker;
    private volatile boolean closed;
    private volatile WatchService watcher;

    CompanionDiscovery(Path directory, Supplier<Result> attempt, BooleanSupplier connected, BooleanSupplier enabled) {
        this.directory = directory;
        this.attempt = attempt;
        this.connected = connected;
        this.enabled = enabled;
        worker = Thread.ofPlatform().daemon().name("TotalDebug Companion discovery").unstarted(this::run);
    }

    void start() { worker.start(); }

    private void run() {
        String lastFailure = null;
        while (!closed) {
            try {
                Files.createDirectories(directory);
                try (var watching = directory.getFileSystem().newWatchService()) {
                    watcher = watching;
                    directory.register(watching, StandardWatchEventKinds.ENTRY_CREATE,
                            StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
                    watch(watching);
                } finally { watcher = null; }
            } catch (ClosedWatchServiceException ignored) {
                if (closed) return;
            } catch (IOException | RuntimeException failure) {
                if (!failure.toString().equals(lastFailure)) {
                    TotalDebug.LOGGER.warn("Companion discovery unavailable: {}", failure.getMessage());
                    lastFailure = failure.toString();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!closed) {
                try { Thread.sleep(1000); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return; }
            }
        }
    }

    private void watch(WatchService watching) throws IOException, InterruptedException {
        var publication = publication();
        boolean check = true;
        boolean previouslyConnected = connected.getAsBoolean();
        boolean previouslyEnabled = false;
        long retryAt = Long.MAX_VALUE;
        int delay = 1;
        while (!closed) {
            boolean allowed = enabled.getAsBoolean();
            boolean nowConnected = connected.getAsBoolean();
            if (allowed && !previouslyEnabled || previouslyConnected && !nowConnected) check = true;
            previouslyEnabled = allowed;
            previouslyConnected = nowConnected;
            if (allowed && (check || System.nanoTime() >= retryAt)) {
                var result = attempt.get();
                previouslyConnected = result == Result.CONNECTED || connected.getAsBoolean();
                retryAt = result == Result.RETRY ? System.nanoTime() + TimeUnit.SECONDS.toNanos(delay) : Long.MAX_VALUE;
                delay = result == Result.RETRY ? Math.min(10, delay * 2) : 1;
                check = false;
            }
            var key = watching.poll(1, TimeUnit.SECONDS);
            if (key == null) continue;
            boolean relevant = false;
            boolean overflow = false;
            for (var event : key.pollEvents()) {
                if (event.kind() == StandardWatchEventKinds.OVERFLOW) { relevant = true; overflow = true; }
                else if (event.context() instanceof Path path && (path.toString().equals(CompanionLaunchContract.INSTANCE_DESCRIPTOR_FILE_NAME)
                        || path.toString().equals(CompanionLaunchContract.INSTANCE_KEY_FILE_NAME))) relevant = true;
            }
            if (!key.reset()) return;
            if (relevant) {
                var latest = publication();
                // Coalesce duplicate OS events, but never infer unchanged contents from metadata alone.
                if (overflow || !latest.equals(publication)) {
                    publication = latest;
                    check = true;
                    retryAt = Long.MAX_VALUE;
                    delay = 1;
                }
            }
        }
    }

    private Publication publication() throws IOException {
        return new Publication(PublishedFile.read(directory.resolve(CompanionLaunchContract.INSTANCE_DESCRIPTOR_FILE_NAME)),
                PublishedFile.read(directory.resolve(CompanionLaunchContract.INSTANCE_KEY_FILE_NAME)));
    }

    @Override public void close() {
        closed = true;
        var watching = watcher;
        if (watching != null) {
            try { watching.close(); }
            catch (IOException failure) { TotalDebug.LOGGER.debug("Unable to close Companion watcher", failure); }
        }
        worker.interrupt();
    }
}
