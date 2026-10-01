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
import java.util.Iterator;
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
 *
 * <p>On Windows a folder cannot be renamed or moved while it or a folder inside it is watched: Companion's own moves of
 * such folders run {@link #pausing}, without watches there.</p>
 */
public final class FileWatch {
    private static final List<Long> RETRY_MILLIS = List.of(1_000L, 5_000L, 30_000L);
    private static final System.Logger LOGGER = System.getLogger(FileWatch.class.getName());
    private static final FileWatch SHARED = new FileWatch();

    /** Runs with no watch on or inside some folders. */
    @FunctionalInterface
    public interface Operation {
        void run() throws IOException;
    }

    /** A folder followed, with the names of its entries that matter and what to tell. */
    private static final class Watched {
        final Path folder;
        final Predicate<Path> names;
        /** Told only of entries created or removed, not of those written, as a listing of the folder. */
        final boolean entriesOnly;
        final Runnable changed;
        /** The folder watched for it: its own, or its nearest existing ancestor; null while it cannot be watched. */
        Path at;
        /** The watch of {@link #at}, which a folder reached through another path, as a link, shares. */
        WatchKey key;
        /** The real folder whose pause holds this registration, even after that folder was moved. */
        Path pausedAt;
        ScheduledFuture<?> retry;
        boolean closed;

        Watched(Path folder, Predicate<Path> names, boolean entriesOnly, Runnable changed) {
            this.folder = folder;
            this.names = names;
            this.entriesOnly = entriesOnly;
            this.changed = changed;
        }
    }

    /** Null where the system has no file watching at all; then every folder is followed by the retries alone. */
    private final WatchService service;
    /** The watches by the real path of their folder, so one reached through a link is watched once. */
    private final Map<Path, WatchKey> keys = new HashMap<>();
    private final List<Watched> watched = new ArrayList<>();
    /** The real paths of the folders no watch may be on or inside, with how many operations pause each. */
    private final Map<Path, Integer> paused = new HashMap<>();

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
        return watch(new Watched(followedFolder(folder), Objects.requireNonNull(names, "names"), false,
                Objects.requireNonNull(changed, "changed")));
    }

    /**
     * Tells {@code changed} after an entry of {@code folder} was created or removed, not when one was written, and
     * whenever events may have been lost; returns what stops it. For a listing of the folder.
     */
    public Runnable watchEntries(Path folder, Runnable changed) {
        return watch(new Watched(followedFolder(folder), name -> true, true, Objects.requireNonNull(changed, "changed")));
    }

    /** Keeps a linked folder's real target when it disappears, so watching its parent can follow its return. */
    private static Path followedFolder(Path folder) {
        Path absolute = folder.toAbsolutePath().normalize();
        Path existing = absolute;
        while (existing != null && !Files.isDirectory(existing)) existing = existing.getParent();
        if (existing != null) {
            try {
                return existing.toRealPath().resolve(existing.relativize(absolute));
            } catch (IOException | RuntimeException unavailable) {
                // The ordinary registration retries paths it cannot resolve yet.
            }
        }
        return absolute;
    }

    /**
     * Runs {@code operation} with no watch on or inside {@code roots}, so it can rename or move them on Windows, then
     * watches them again and tells their followers, since the operation changed what they follow. Blocking.
     */
    public void pausing(List<Path> roots, Operation operation) throws IOException {
        List<Path> real = new ArrayList<>();
        for (Path root : roots) real.add(root.toRealPath());
        synchronized (this) {
            real.forEach(root -> this.paused.merge(root, 1, Integer::sum));
            for (Iterator<Map.Entry<Path, WatchKey>> keys = this.keys.entrySet().iterator(); keys.hasNext(); ) {
                Map.Entry<Path, WatchKey> entry = keys.next();
                if (!paused(entry.getKey())) continue;
                keys.remove();
                entry.getValue().cancel();
                for (Watched followed : this.watched) {
                    if (followed.key != entry.getValue()) continue;
                    followed.key = null;
                    followed.at = null;
                    followed.pausedAt = entry.getKey();
                }
            }
        }
        try {
            operation.run();
        } finally {
            List<Runnable> tell = new ArrayList<>();
            synchronized (this) {
                real.forEach(root -> this.paused.computeIfPresent(root, (ignored, count) -> count == 1 ? null : count - 1));
                for (Watched followed : this.watched) {
                    if (followed.key != null) continue;
                    if (followed.pausedAt == null || paused(followed.pausedAt)) continue;
                    if (followed.retry != null) followed.retry.cancel(false);
                    place(followed, 0);
                    if (followed.pausedAt == null) tell.add(followed.changed);
                }
            }
            // Also where the operation failed: it may have changed something before.
            tell.forEach(FileWatch::tell);
        }
    }

    private Runnable watch(Watched followed) {
        synchronized (this) {
            this.watched.add(followed);
            place(followed, 0);
        }
        return () -> {
            synchronized (this) {
                followed.closed = true;
                this.watched.remove(followed);
                if (followed.retry != null) followed.retry.cancel(false);
                release(followed.key);
            }
        };
    }

    /** Watches {@code followed}'s folder or its nearest existing ancestor, or tries again later. Under the lock. */
    private void place(Watched followed, int attempt) {
        followed.pausedAt = null;
        WatchKey previous = followed.key;
        List<WatchKey> passed = new ArrayList<>();
        Path at;
        WatchKey key;
        while (true) {
            at = followed.folder;
            while (at != null && !Files.isDirectory(at)) at = at.getParent();
            key = at == null ? null : register(at, followed);
            if (key == null) {
                at = null;
                break;
            }
            // A folder created between looking and watching, as a game makes its folders at once, was not seen: once
            // watched, the folder beneath is looked for again.
            if (at.equals(followed.folder) || !Files.isDirectory(followed.folder.getRoot().resolve(
                    followed.folder.subpath(0, at.getNameCount() + 1)))) break;
            passed.add(key);
        }
        followed.at = at;
        followed.key = key;
        if (previous != null && previous != key) release(previous);
        // Ancestors watched on the way down that nothing else follows are let go.
        passed.forEach(this::release);
        if (key == null) retryLater(followed, attempt);
    }

    /** The watch of {@code folder}, shared with every path that leads to it; null where it cannot be watched. */
    private WatchKey register(Path folder, Watched followed) {
        if (this.service == null) return null;
        try {
            Path real = folder.toRealPath();
            // Watched again once the operation that pauses it ended.
            if (paused(real)) {
                followed.pausedAt = real;
                return null;
            }
            WatchKey key = this.keys.get(real);
            if (key != null && key.isValid()) return key;
            key = real.register(this.service, StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
            this.keys.put(real, key);
            return key;
        } catch (IOException | RuntimeException unwatchable) {
            return null;
        }
    }

    /** Whether {@code real} is a paused folder or inside one. Under the lock. */
    private boolean paused(Path real) {
        for (Path root : this.paused.keySet()) {
            if (real.startsWith(root)) return true;
        }
        return false;
    }

    /** Stops the watch {@code key} once nothing followed uses it. Under the lock. */
    private void release(WatchKey key) {
        if (key == null) return;
        for (Watched followed : this.watched) {
            if (followed.key == key) return;
        }
        this.keys.values().remove(key);
        key.cancel();
    }

    /** Tries to watch {@code followed} again after a while, and tells it then, so it reads what it follows. Under the lock. */
    private void retryLater(Watched followed, int attempt) {
        long delay = RETRY_MILLIS.get(Math.min(attempt, RETRY_MILLIS.size() - 1));
        followed.retry = Workers.later(delay, Workers.files(), () -> {
            synchronized (this) {
                if (followed.closed) return;
                if (followed.pausedAt != null && paused(followed.pausedAt)) {
                    retryLater(followed, attempt + 1);
                    return;
                }
                place(followed, attempt + 1);
                if (followed.pausedAt != null) return;
            }
            tell(followed.changed);
        });
    }

    /** Tells a follower; one follower's failure does not stop the watch of every other. */
    private static void tell(Runnable changed) {
        try {
            changed.run();
        } catch (RuntimeException failure) {
            LOGGER.log(System.Logger.Level.WARNING, "A follower of a watched folder failed", failure);
        }
    }

    /** Whether an entry named among {@code names} matters to {@code followed}; a follower's failing names tell nothing. */
    private static boolean concerns(Watched followed, List<Path> names) {
        try {
            return names.stream().anyMatch(followed.names);
        } catch (RuntimeException failure) {
            // One follower's failure does not stop the watch of every other.
            LOGGER.log(System.Logger.Level.WARNING, "A follower of a watched folder failed", failure);
            return false;
        }
    }

    private void run() {
        try {
            while (true) {
                WatchKey key = this.service.take();
                List<Path> names = new ArrayList<>();
                List<Path> entries = new ArrayList<>();
                boolean lost = false;
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) lost = true;
                    else if (event.context() instanceof Path name) {
                        names.add(name);
                        if (event.kind() != StandardWatchEventKinds.ENTRY_MODIFY) entries.add(name);
                    }
                }
                List<Runnable> tell = new ArrayList<>();
                synchronized (this) {
                    boolean gone = !key.reset();
                    if (gone) this.keys.values().remove(key);
                    for (Watched followed : List.copyOf(this.watched)) {
                        if (followed.key != key) continue;
                        if (gone || !followed.at.equals(followed.folder)) {
                            // Removed, or an ancestor whose entry toward the folder may have appeared: placed again.
                            Path toward = gone ? null : followed.folder.getName(followed.at.getNameCount());
                            if (gone || lost || names.contains(toward)) {
                                if (gone) followed.key = null;
                                place(followed, 0);
                                tell.add(followed.changed);
                            }
                        } else if (lost || concerns(followed, followed.entriesOnly ? entries : names)) {
                            tell.add(followed.changed);
                        }
                    }
                }
                tell.forEach(FileWatch::tell);
            }
        } catch (InterruptedException | ClosedWatchServiceException stopped) {
            // The application ends.
        }
    }
}
