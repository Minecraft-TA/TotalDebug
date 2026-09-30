package com.github.minecraft_ta.totalDebugCompanion.util;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Predicate;

/**
 * The one watcher of the folders Companion follows (docs/SYSTEMS.md, section 2). A folder that does not exist yet, as a
 * game's before it first ran, is watched through its nearest existing ancestor and watched itself once it appears; one
 * removed while watched is watched that way again. Where a folder cannot be watched at all, it is tried again after 1
 * and 5 seconds and every 30 seconds after, and its follower is told each time, so it reads what it follows then.
 * Followers are told on the watcher's thread and hand the work on at once.
 */
public final class FileWatch {
    private static final List<Long> RETRY_MILLIS = List.of(1_000L, 5_000L, 30_000L);
    private static final System.Logger LOGGER = System.getLogger(FileWatch.class.getName());
    private static final FileWatch SHARED = new FileWatch();

    /** A folder followed, with the names of its entries that matter and what to tell. */
    private static final class Watched {
        final Path folder;
        final Predicate<Path> names;
        final Runnable changed;
        /** The folder watched for it: its own, or its nearest existing ancestor; null while it cannot be watched. */
        Path at;
        ScheduledFuture<?> retry;
        boolean closed;

        Watched(Path folder, Predicate<Path> names, Runnable changed) {
            this.folder = folder;
            this.names = names;
            this.changed = changed;
        }
    }

    /** Null where the system has no file watching at all; then every folder is followed by the retries alone. */
    private final WatchService service;
    private final Map<Path, WatchKey> keys = new HashMap<>();
    private final Map<WatchKey, Path> folders = new HashMap<>();
    private final List<Watched> watched = new ArrayList<>();

    private FileWatch() {
        WatchService created = null;
        try {
            created = FileSystems.getDefault().newWatchService();
        } catch (IOException | RuntimeException unavailable) {
            LOGGER.log(System.Logger.Level.WARNING, "Files are read every 30 seconds, not watched: " + unavailable.getMessage());
        }
        this.service = created;
        if (created != null) Thread.ofPlatform().daemon().name("Companion file watch").start(this::run);
    }

    /** The application's watcher. */
    public static FileWatch shared() {
        return SHARED;
    }

    /**
     * Tells {@code changed} after an entry of {@code folder} whose name {@code names} accepts was created, changed or
     * removed, and whenever events may have been lost; returns what stops it.
     */
    public Runnable watch(Path folder, Predicate<Path> names, Runnable changed) {
        Watched followed = new Watched(folder.toAbsolutePath().normalize(), Objects.requireNonNull(names, "names"),
                Objects.requireNonNull(changed, "changed"));
        synchronized (this) {
            this.watched.add(followed);
            place(followed, 0);
        }
        return () -> {
            synchronized (this) {
                followed.closed = true;
                this.watched.remove(followed);
                if (followed.retry != null) followed.retry.cancel(false);
                release(followed.at);
            }
        };
    }

    /** Watches {@code followed}'s folder or its nearest existing ancestor, or tries again later. Under the lock. */
    private void place(Watched followed, int attempt) {
        Path previous = followed.at;
        Path at;
        do {
            at = followed.folder;
            while (at != null && !Files.isDirectory(at)) at = at.getParent();
            if (at == null || !register(at)) {
                at = null;
                break;
            }
            // A folder created between looking and watching, as a game makes its folders at once, was not seen: once
            // watched, the folder beneath is looked for again.
        } while (!at.equals(followed.folder) && Files.isDirectory(followed.folder.getRoot().resolve(
                followed.folder.subpath(0, at.getNameCount() + 1))));
        followed.at = at;
        if (previous != null && !previous.equals(at)) release(previous);
        // Ancestors registered on the way down that no longer hold anything are let go.
        for (Path passed = at == null ? null : at.getParent(); passed != null; passed = passed.getParent()) release(passed);
        if (at == null) retryLater(followed, attempt);
    }

    private boolean register(Path folder) {
        if (this.service == null) return false;
        if (this.keys.containsKey(folder)) return true;
        try {
            WatchKey key = folder.register(this.service, StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
            this.keys.put(folder, key);
            this.folders.put(key, folder);
            return true;
        } catch (IOException | RuntimeException unwatchable) {
            return false;
        }
    }

    /** Stops watching {@code folder} once nothing followed is watched there. Under the lock. */
    private void release(Path folder) {
        if (folder == null) return;
        for (Watched followed : this.watched) {
            if (folder.equals(followed.at)) return;
        }
        WatchKey key = this.keys.remove(folder);
        if (key == null) return;
        this.folders.remove(key);
        key.cancel();
    }

    /** Tries to watch {@code followed} again after a while, and tells it then, so it reads what it follows. Under the lock. */
    private void retryLater(Watched followed, int attempt) {
        long delay = RETRY_MILLIS.get(Math.min(attempt, RETRY_MILLIS.size() - 1));
        followed.retry = Workers.later(delay, Workers.files(), () -> {
            synchronized (this) {
                if (followed.closed) return;
                place(followed, attempt + 1);
            }
            followed.changed.run();
        });
    }

    private void run() {
        try {
            while (true) {
                WatchKey key = this.service.take();
                List<Path> names = new ArrayList<>();
                boolean lost = false;
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) lost = true;
                    else if (event.context() instanceof Path name) names.add(name);
                }
                List<Runnable> tell = new ArrayList<>();
                synchronized (this) {
                    Path folder = this.folders.get(key);
                    boolean gone = !key.reset();
                    if (folder != null) {
                        if (gone) {
                            this.keys.remove(folder);
                            this.folders.remove(key);
                        }
                        for (Watched followed : List.copyOf(this.watched)) {
                            if (!folder.equals(followed.at)) continue;
                            if (gone || !folder.equals(followed.folder)) {
                                // Removed, or an ancestor whose entry toward the folder may have appeared: placed again.
                                Path toward = gone ? null : followed.folder.getName(folder.getNameCount());
                                if (gone || lost || names.contains(toward)) {
                                    place(followed, 0);
                                    tell.add(followed.changed);
                                }
                            } else if (lost || names.stream().anyMatch(followed.names)) {
                                tell.add(followed.changed);
                            }
                        }
                    }
                }
                tell.forEach(Runnable::run);
            }
        } catch (InterruptedException | ClosedWatchServiceException stopped) {
            // The application ends.
        }
    }
}
