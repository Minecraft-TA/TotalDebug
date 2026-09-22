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
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Shares one watcher thread across materialized directories; subscriptions own their registrations. */
public final class FileUtils {
    private static WatchService service;
    private static final Map<Path, Registration> registrations = new HashMap<>();
    private static final Map<Path, Integer> pausedRoots = new HashMap<>();
    // Native registration/cancellation can block. Never hold the state monitor while doing it.
    private static final Object registrationLock = new Object();

    @FunctionalInterface interface DirectoryRegistration {
        WatchKey register(Path path, WatchService service) throws IOException;
    }

    private static final class Registration {
        final Set<Runnable> listeners = new HashSet<>();
        final DirectoryRegistration registrar;
        WatchKey key;
        Registration(DirectoryRegistration registrar) { this.registrar = registrar; }
    }

    public static Runnable startNewDirectoryWatcher(Path directory, Runnable onChange) {
        return startNewDirectoryWatcher(directory, onChange,
                (path, watching) -> path.register(watching, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE));
    }

    static Runnable startNewDirectoryWatcher(Path directory, Runnable onChange, DirectoryRegistration register) {
        Path path;
        try { path = directory.toRealPath(); }
        catch (IOException failure) { throw new IllegalStateException("Unable to watch directory " + directory, failure); }
        Registration registration;
        boolean added;
        synchronized (FileUtils.class) {
            registration = registrations.computeIfAbsent(path, ignored -> new Registration(register));
            added = registration.listeners.add(onChange);
        }
        try { register(path, registration); }
        catch (IOException | RuntimeException failure) {
            if (added) unsubscribe(path, onChange);
            throw new IllegalStateException("Unable to watch directory " + path, failure);
        }
        return () -> unsubscribe(path, onChange);
    }

    private static boolean register(Path path, Registration registration) throws IOException {
        synchronized (registrationLock) {
            WatchService current;
            synchronized (FileUtils.class) {
                if (registrations.get(path) != registration || registration.listeners.isEmpty()
                        || registration.key != null && registration.key.isValid() || paused(path)) return false;
                current = service;
            }
            if (current == null) {
                current = FileSystems.getDefault().newWatchService();
                synchronized (FileUtils.class) { service = current; }
                WatchService watching = current;
                Thread.ofPlatform().daemon().name("directory-watcher").start(() -> watch(watching));
            }
            WatchKey key = registration.registrar.register(path, current);
            synchronized (FileUtils.class) {
                if (registrations.get(path) == registration && !registration.listeners.isEmpty() && !paused(path)) {
                    registration.key = key;
                    return true;
                }
            }
            key.cancel();
            return false;
        }
    }

    private static boolean paused(Path path) { return pausedRoots.keySet().stream().anyMatch(path::startsWith); }

    @FunctionalInterface public interface FileOperation { void run() throws IOException; }

    /** Windows cannot rename a directory while a descendant has an open watch handle. */
    public static void withPausedDirectoryWatchers(List<Path> roots, FileOperation operation) throws IOException {
        Set<Path> paths = new HashSet<>();
        for (Path root : roots) paths.add(root.toRealPath());
        synchronized (FileUtils.class) {
            paths.forEach(path -> pausedRoots.merge(path, 1, Integer::sum));
        }
        try {
            // Drain in-flight registration before moving; it sees the pause and cancels its result.
            synchronized (registrationLock) {
                var keys = new ArrayList<WatchKey>();
                synchronized (FileUtils.class) {
                    registrations.forEach((path, registration) -> {
                        if (paths.stream().anyMatch(path::startsWith) && registration.key != null) {
                            keys.add(registration.key);
                            registration.key = null;
                        }
                    });
                }
                keys.forEach(WatchKey::cancel);
            }
            operation.run();
        }
        finally {
            Set<Runnable> callbacks = new HashSet<>();
            Map<Path, Registration> affected = new HashMap<>();
            synchronized (FileUtils.class) {
                paths.forEach(path -> pausedRoots.computeIfPresent(path, (ignored, count) -> count == 1 ? null : count - 1));
                registrations.forEach((path, registration) -> {
                    if (!paths.stream().anyMatch(path::startsWith)) return;
                    callbacks.addAll(registration.listeners);
                    affected.put(path, registration);
                });
            }
            affected.forEach((path, registration) -> {
                if (Files.isDirectory(path)) {
                    try { register(path, registration); }
                    catch (IOException failure) { logFailure(failure); }
                }
            });
            // The operation may have changed contents while events were paused, including on failure.
            callbacks.forEach(FileUtils::notifyListener);
        }
    }

    private static void watch(WatchService current) {
        try {
            while (true) {
                WatchKey key = current.poll(1, TimeUnit.SECONDS);
                Set<Runnable> callbacks = new HashSet<>();
                if (key != null) {
                    boolean changed = !key.pollEvents().isEmpty();
                    boolean valid = key.reset();
                    synchronized (FileUtils.class) {
                        registrations.values().stream().filter(registration -> registration.key == key).forEach(registration -> {
                            if (changed || !valid) callbacks.addAll(registration.listeners);
                            if (!valid) registration.key = null;
                        });
                    }
                }
                Map<Path, Registration> pending;
                synchronized (FileUtils.class) {
                    if (service != current) return;
                    pending = new HashMap<>(registrations);
                }
                pending.forEach((path, registration) -> {
                    try {
                        if (register(path, registration)) synchronized (FileUtils.class) { callbacks.addAll(registration.listeners); }
                    } catch (IOException unavailable) {
                        // Keep the subscription while an externally removed directory is unavailable.
                    }
                });
                callbacks.forEach(FileUtils::notifyListener);
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

    private static void unsubscribe(Path path, Runnable callback) {
        synchronized (FileUtils.class) {
            var registration = registrations.get(path);
            if (registration == null) return;
            registration.listeners.remove(callback);
            if (!registration.listeners.isEmpty()) return;
            // Keep empty registrations visible to a concurrent rename pause until cleanup drains them.
        }
        CompletableFuture.runAsync(FileUtils::closeUnusedRegistrations);
    }

    private static void closeUnusedRegistrations() {
        synchronized (registrationLock) {
            var keys = new ArrayList<WatchKey>();
            WatchService unused = null;
            synchronized (FileUtils.class) {
                registrations.values().removeIf(registration -> {
                    if (!registration.listeners.isEmpty()) return false;
                    if (registration.key != null) keys.add(registration.key);
                    return true;
                });
                if (registrations.isEmpty()) { unused = service; service = null; }
            }
            keys.forEach(WatchKey::cancel);
            if (unused != null) try { unused.close(); } catch (IOException failure) { logFailure(failure); }
        }
    }
}
