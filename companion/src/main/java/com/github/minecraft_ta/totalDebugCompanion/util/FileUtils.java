package com.github.minecraft_ta.totalDebugCompanion.util;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Shares one watcher thread across materialized directories; subscriptions own their registrations. */
public final class FileUtils {
    private static WatchService service;
    private static final Map<WatchKey, Set<Runnable>> listeners = new HashMap<>();

    public static synchronized Runnable startNewDirectoryWatcher(Path directory, Runnable onChange) {
        try {
            if (service == null) {
                service = FileSystems.getDefault().newWatchService();
                WatchService current = service;
                Thread thread = new Thread(() -> watch(current), "directory-watcher");
                thread.setDaemon(true);
                thread.start();
            }
            WatchKey key = directory.register(service, StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE);
            listeners.computeIfAbsent(key, ignored -> new HashSet<>()).add(onChange);
            return () -> unsubscribe(key, onChange);
        } catch (IOException failure) { throw new IllegalStateException("Unable to watch directory " + directory, failure); }
    }

    private static void watch(WatchService current) {
        try {
            while (true) {
                WatchKey key = current.take();
                boolean changed = !key.pollEvents().isEmpty();
                Set<Runnable> callbacks;
                synchronized (FileUtils.class) { callbacks = Set.copyOf(listeners.getOrDefault(key, Set.of())); }
                if (changed) callbacks.forEach(callback -> {
                    try { callback.run(); }
                    catch (RuntimeException failure) { System.getLogger(FileUtils.class.getName()).log(System.Logger.Level.WARNING, "Directory refresh failed", failure); }
                });
                key.reset();
            }
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch (ClosedWatchServiceException ignored) { }
    }

    private static synchronized void unsubscribe(WatchKey key, Runnable callback) {
        var callbacks = listeners.get(key);
        if (callbacks == null) return;
        callbacks.remove(callback);
        if (!callbacks.isEmpty()) return;
        listeners.remove(key);
        key.cancel();
        if (listeners.isEmpty() && service != null) {
            try { service.close(); } catch (IOException ignored) { }
            service = null;
        }
    }
}
