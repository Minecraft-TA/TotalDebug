package com.github.minecraft_ta.totalDebugCompanion.catalog;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The keys {@code options.txt} assigns, watched: whoever writes the file, the game when a key is rebound in its controls
 * screen, Companion or an editor, the listeners hear of it once the assignments differ from those read before. Another
 * option written, such as the volume, tells nobody. The file's folder is watched, so a file replaced by a rename, as the
 * game and Companion write it, is seen; a deleted file assigns nothing. A file that cannot be read keeps the assignments
 * read before, and is read again after 1, 5 and 30 seconds and at its next change.
 */
public final class KeyAssignments implements AutoCloseable {
    private static final long SETTLE_MILLIS = 300;
    private static final List<Long> RETRY_MILLIS = List.of(1_000L, 5_000L, 30_000L);

    private final Path options;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(task -> Thread.ofPlatform()
            .daemon()
            .name("Companion options.txt")
            .unstarted(task));
    /** Null where the game's folder cannot be watched, as before it exists. */
    private final WatchService watcher;
    private ScheduledFuture<?> pending;
    /** Counts writes seen; only a read for the latest may tell, so a retry of an earlier one cannot read a write in parts. */
    private long generation;
    /** The assignments read last, or null before the first read. */
    private Map<String, KeyBindings.Assignment> read;

    /** Watches {@code options}; where its folder, the game's, cannot be watched, nothing is told. */
    public KeyAssignments(Path options) {
        this.options = Objects.requireNonNull(options, "options").toAbsolutePath().normalize();
        this.watcher = watcher(this.options.getParent());
        if (this.watcher == null) return;
        Thread.ofPlatform().daemon().name("Companion options.txt watcher").start(this::watch);
        this.timer.execute(() -> read(0, 0));
    }

    private static WatchService watcher(Path folder) {
        WatchService watcher = null;
        try {
            watcher = FileSystems.getDefault().newWatchService();
            folder.register(watcher, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);
            return watcher;
        } catch (IOException | RuntimeException unwatchable) {
            System.getLogger(KeyAssignments.class.getName()).log(System.Logger.Level.WARNING,
                    "Key assignments in " + folder + " are read when a page is shown, not watched: " + unwatchable.getMessage());
            if (watcher != null) {
                try {
                    watcher.close();
                } catch (IOException ignored) {
                    // Nothing was registered with it.
                }
            }
            return null;
        }
    }

    /** Runs {@code listener} on a Companion thread after the assignments changed; returns its removal. */
    public Runnable addListener(Runnable listener) {
        this.listeners.add(Objects.requireNonNull(listener, "listener"));
        return () -> this.listeners.remove(listener);
    }

    private void watch() {
        try {
            while (true) {
                WatchKey key = this.watcher.take();
                boolean ours = false;
                for (WatchEvent<?> event : key.pollEvents()) {
                    // Lost events may have been about the file too.
                    ours |= event.kind() == StandardWatchEventKinds.OVERFLOW
                            || event.context() instanceof Path name && name.equals(this.options.getFileName());
                }
                if (ours) written();
                if (!key.reset()) return;
            }
        } catch (InterruptedException | ClosedWatchServiceException closed) {
            // Closed with the project.
        }
    }

    /** Reads the file once it has not been written for {@value #SETTLE_MILLIS} ms, as a write can come in parts. */
    private synchronized void written() {
        long generation = ++this.generation;
        schedule(() -> read(0, generation), SETTLE_MILLIS);
    }

    /** Replaces the read waiting, if any, with {@code read} in {@code millis}. */
    private synchronized void schedule(Runnable read, long millis) {
        if (this.pending != null) this.pending.cancel(false);
        try {
            this.pending = this.timer.schedule(read, millis, TimeUnit.MILLISECONDS);
        } catch (RuntimeException closed) {
            // Closed with the project.
        }
    }

    /** Reads the file for write {@code generation}; a newer write makes it tell nothing and try no more. */
    private void read(int attempt, long generation) {
        Map<String, KeyBindings.Assignment> now;
        try {
            now = KeyBindings.readOptions(this.options);
        } catch (IOException | RuntimeException unreadable) {
            synchronized (this) {
                if (generation == this.generation && attempt < RETRY_MILLIS.size()) {
                    schedule(() -> read(attempt + 1, generation), RETRY_MILLIS.get(attempt));
                }
            }
            return;
        }
        boolean changed;
        synchronized (this) {
            if (generation != this.generation) return;
            changed = this.read != null && !this.read.equals(now);
            this.read = now;
        }
        if (changed) this.listeners.forEach(Runnable::run);
    }

    @Override
    public void close() throws IOException {
        this.timer.shutdownNow();
        if (this.watcher != null) this.watcher.close();
    }
}
