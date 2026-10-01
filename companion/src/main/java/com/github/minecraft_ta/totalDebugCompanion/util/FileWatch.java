package com.github.minecraft_ta.totalDebugCompanion.util;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tells when a file Companion handed to another program is written, such as a texture opened in an image editor, whose
 * saves are taken at once (docs/SYSTEMS.md, section 2). Nothing else is watched: what others write is read when it is
 * shown and when the user comes back to Companion. Followers are told on the watcher's thread and hand the work on at
 * once.
 */
public final class FileWatch {
    private static final System.Logger LOGGER = System.getLogger(FileWatch.class.getName());
    private static final FileWatch SHARED = new FileWatch();

    /** A follower: the name of its file in the watched folder, and what to tell. */
    private record Watched(Path name, Runnable written) {
    }

    /** Made with the first watch. */
    private WatchService service;
    /** The followers of each watched folder, by its watch, which every file of the folder shares. */
    private final Map<WatchKey, List<Watched>> watched = new HashMap<>();

    private FileWatch() {
    }

    /** The application's watcher. */
    public static FileWatch shared() {
        return SHARED;
    }

    /**
     * Tells {@code written} after {@code file} was written, created or removed, and whenever its folder's events were
     * lost or the folder was removed; returns what stops it. Fails where the file's folder cannot be watched.
     */
    public synchronized Runnable watch(Path file, Runnable written) throws IOException {
        if (this.service == null) {
            this.service = FileSystems.getDefault().newWatchService();
            Thread.ofPlatform().daemon().name("Companion file watch").start(this::run);
        }
        // A folder watched already keeps its watch.
        WatchKey key = file.getParent().register(this.service, StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
        Watched follower = new Watched(file.getFileName(), written);
        this.watched.computeIfAbsent(key, ignored -> new ArrayList<>()).add(follower);
        return () -> stop(key, follower);
    }

    private synchronized void stop(WatchKey key, Watched follower) {
        List<Watched> followers = this.watched.get(key);
        if (followers == null || !followers.remove(follower) || !followers.isEmpty()) return;
        this.watched.remove(key);
        key.cancel();
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
                    // A removed folder is watched no more: its followers are told once and find their file gone.
                    boolean gone = !key.reset();
                    List<Watched> followers = gone ? this.watched.remove(key) : this.watched.get(key);
                    if (followers != null) {
                        for (Watched follower : followers) {
                            if (gone || lost || names.contains(follower.name())) tell.add(follower.written());
                        }
                    }
                }
                tell.forEach(FileWatch::tell);
            }
        } catch (InterruptedException | ClosedWatchServiceException stopped) {
            // The application ends.
        }
    }

    /** Tells a follower; one follower's failure does not stop the watch of every other. */
    private static void tell(Runnable written) {
        try {
            written.run();
        } catch (RuntimeException failure) {
            LOGGER.log(System.Logger.Level.WARNING, "A follower of a watched file failed", failure);
        }
    }
}
