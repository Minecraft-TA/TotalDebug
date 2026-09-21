package com.github.minecraft_ta.totalDebugCompanion.util;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Shares one watcher thread across materialized directories; subscriptions own their registrations. */
public final class FileUtils {
    private static WatchService service;
    private static final Map<Path, Registration> registrations = new HashMap<>();
    private static final Map<Path, Integer> pausedRoots = new HashMap<>();

    private static final class Registration {
        final Set<Runnable> listeners = new HashSet<>();
        WatchKey key;
    }

    public static synchronized Runnable startNewDirectoryWatcher(Path directory, Runnable onChange) {
        Path path;
        try { path = directory.toRealPath(); }
        catch (IOException failure) { throw new IllegalStateException("Unable to watch directory " + directory, failure); }
        var registration = registrations.computeIfAbsent(path, ignored -> new Registration());
        try { register(path, registration); }
        catch (IOException failure) {
            if (registration.listeners.isEmpty()) registrations.remove(path);
            throw new IllegalStateException("Unable to watch directory " + path, failure);
        }
        registration.listeners.add(onChange);
        return () -> unsubscribe(path, onChange);
    }

    private static void register(Path path, Registration registration) throws IOException {
        if (registration.key != null && registration.key.isValid() || pausedRoots.keySet().stream().anyMatch(path::startsWith)) return;
        if (service == null) {
            service = FileSystems.getDefault().newWatchService();
            WatchService current = service;
            Thread thread = new Thread(() -> watch(current), "directory-watcher");
            thread.setDaemon(true);
            thread.start();
        }
        registration.key = path.register(service, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE);
    }

    @FunctionalInterface public interface FileOperation { void run() throws IOException; }

    /** Windows cannot rename a directory while a descendant has an open watch handle. */
    public static void withPausedDirectoryWatchers(List<Path> roots, FileOperation operation) throws IOException {
        Set<Path> paths = new HashSet<>();
        for (Path root : roots) paths.add(root.toRealPath());
        synchronized (FileUtils.class) {
            paths.forEach(path -> pausedRoots.merge(path, 1, Integer::sum));
            registrations.forEach((path, registration) -> {
                if (paths.stream().anyMatch(path::startsWith) && registration.key != null) {
                    registration.key.cancel();
                    registration.key = null;
                }
            });
        }
        try { operation.run(); }
        finally {
            Set<Runnable> callbacks = new HashSet<>();
            synchronized (FileUtils.class) {
                paths.forEach(path -> pausedRoots.computeIfPresent(path, (ignored, count) -> count == 1 ? null : count - 1));
                registrations.forEach((path, registration) -> {
                    if (!paths.stream().anyMatch(path::startsWith)) return;
                    callbacks.addAll(registration.listeners);
                    if (Files.isDirectory(path)) {
                        try { register(path, registration); }
                        catch (IOException failure) { logFailure(failure); }
                    }
                });
            }
            // The operation may have changed contents while events were paused, including on failure.
            callbacks.forEach(FileUtils::notifyListener);
        }
    }

    private static void watch(WatchService current) {
        try {
            while (true) {
                WatchKey key = current.take();
                boolean changed = !key.pollEvents().isEmpty();
                boolean valid = key.reset();
                Set<Runnable> callbacks = new HashSet<>();
                synchronized (FileUtils.class) {
                    registrations.values().stream().filter(registration -> registration.key == key).forEach(registration -> {
                        callbacks.addAll(registration.listeners);
                        if (!valid) registration.key = null;
                    });
                }
                if (changed || !valid) callbacks.forEach(FileUtils::notifyListener);
            }
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch (ClosedWatchServiceException ignored) { }
    }

    private static void notifyListener(Runnable callback) {
        try { callback.run(); } catch (RuntimeException failure) { logFailure(failure); }
    }

    private static void logFailure(Exception failure) {
        System.getLogger(FileUtils.class.getName()).log(System.Logger.Level.WARNING, "Directory refresh failed", failure);
    }

    private static synchronized void unsubscribe(Path path, Runnable callback) {
        var registration = registrations.get(path);
        if (registration == null) return;
        registration.listeners.remove(callback);
        if (!registration.listeners.isEmpty()) return;
        registrations.remove(path);
        if (registration.key != null) registration.key.cancel();
        if (registrations.isEmpty() && service != null) {
            try { service.close(); } catch (IOException ignored) { }
            service = null;
        }
    }
}
