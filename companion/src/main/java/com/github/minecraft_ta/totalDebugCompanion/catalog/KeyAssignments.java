package com.github.minecraft_ta.totalDebugCompanion.catalog;

import com.github.minecraft_ta.totalDebugCompanion.util.Signal;
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
 * read before, and is read again after 1, 5 and 30 seconds and at its next change. Companion's own writes are read at once
 * ({@link #readNow()}), so a page shows them without waiting for the watch.
 */
public final class KeyAssignments implements AutoCloseable {
    private static final long SETTLE_MILLIS = 300;
    private static final List<Long> RETRY_MILLIS = List.of(1_000L, 5_000L, 30_000L);
    private static final System.Logger LOGGER = System.getLogger(KeyAssignments.class.getName());

    private final Path options;
    private final Signal changed = new Signal();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(task -> Thread.ofPlatform()
            .daemon()
            .name("Companion options.txt")
            .unstarted(task));
    /** Null only where the system has no file watching at all. */
    private final WatchService watcher;
    /**
     * Whether the game's folder is watched now: not before it exists, or after it was removed; then its watch is tried
     * again after 1 and 5 seconds and every 30 seconds after.
     */
    private volatile boolean watching;
    private ScheduledFuture<?> pending;
    /** Counts writes seen; only a read for the latest may tell, so a retry of an earlier one cannot read a write in parts. */
    private long generation;
    /** The assignments read last, or null before the first read. */
    private Map<String, KeyBindings.Assignment> read;
    /** Whether the last read failed: a page may show that failure, so the next read that succeeds is told. */
    private boolean unreadable;

    /** Watches {@code options}, whose folder, the game's, may not exist yet: it is watched once it does. */
    public KeyAssignments(Path options) {
        this.options = Objects.requireNonNull(options, "options").toAbsolutePath().normalize();
        this.watcher = watchService();
        if (this.watcher != null) {
            Thread.ofPlatform().daemon().name("Companion options.txt watcher").start(this::watch);
            if (!register()) registerLater(0);
        }
        this.timer.execute(() -> read(0, 0));
    }

    /**
     * The keys the file assigns: as last read while its folder is watched, and read now where it has not been read yet
     * or is not watched, since then a change would go unseen. The first read, whoever makes it, is what the next change
     * is told against, so a follower that read before this owner did still hears of a later change; after it, only
     * this owner's own reads are, so every follower hears of a change it has not seen. Blocking where it reads.
     */
    public Map<String, KeyBindings.Assignment> assignments() throws IOException {
        synchronized (this) {
            if (this.read != null && this.watching) return this.read;
        }
        Map<String, KeyBindings.Assignment> now = KeyBindings.readOptions(this.options);
        synchronized (this) {
            if (this.read == null) this.read = now;
        }
        return now;
    }

    private static WatchService watchService() {
        try {
            return FileSystems.getDefault().newWatchService();
        } catch (IOException | RuntimeException unavailable) {
            LOGGER.log(System.Logger.Level.WARNING, "Key assignments are read when asked, not watched: " + unavailable.getMessage());
            return null;
        }
    }

    /** Watches the game's folder; false where it cannot be, as before it exists. */
    private boolean register() {
        try {
            this.options.getParent().register(this.watcher, StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
            this.watching = true;
            return true;
        } catch (IOException | RuntimeException unwatchable) {
            return false;
        }
    }

    /** Tries to watch the game's folder again after a while, then reads what the file assigns by then. */
    private void registerLater(int attempt) {
        long delay = RETRY_MILLIS.get(Math.min(attempt, RETRY_MILLIS.size() - 1));
        try {
            this.timer.schedule(() -> {
                if (register()) written();
                else registerLater(attempt + 1);
            }, delay, TimeUnit.MILLISECONDS);
        } catch (RuntimeException closed) {
            // Closed with the project.
        }
    }

    /** Fires on a Companion thread after the assignments changed. */
    public Signal changed() {
        return this.changed;
    }

    /** Reads the file now rather than once a watch saw it settle, as after Companion wrote it. */
    public synchronized void readNow() {
        long generation = ++this.generation;
        schedule(() -> read(0, generation), 0);
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
                if (!key.reset()) {
                    // The folder was removed: what the file assigns is read now, and the folder watched again once it is back.
                    this.watching = false;
                    written();
                    registerLater(0);
                }
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
                if (generation == this.generation) this.unreadable = true;
                if (generation == this.generation && attempt < RETRY_MILLIS.size()) {
                    schedule(() -> read(attempt + 1, generation), RETRY_MILLIS.get(attempt));
                }
            }
            return;
        }
        boolean changed;
        synchronized (this) {
            // A newer write is read after this one; what this one read is still what the next compares with.
            if (generation != this.generation) {
                if (this.read == null) this.read = now;
                return;
            }
            changed = this.unreadable || this.read != null && !this.read.equals(now);
            this.read = now;
            this.unreadable = false;
        }
        if (changed) this.changed.fire();
    }

    /** Stops watching. It never fails, so a project's shutdown goes on to its queued writes after it. */
    @Override
    public void close() {
        this.timer.shutdownNow();
        if (this.watcher == null) return;
        try {
            this.watcher.close();
        } catch (IOException failure) {
            LOGGER.log(System.Logger.Level.WARNING, "The watcher of " + this.options + " did not close: " + failure.getMessage());
        }
    }
}
