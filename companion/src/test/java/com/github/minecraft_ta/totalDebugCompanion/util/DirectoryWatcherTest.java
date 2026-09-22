package com.github.minecraft_ta.totalDebugCompanion.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.WatchKey;
import java.nio.file.StandardWatchEventKinds;
import javax.swing.SwingUtilities;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class DirectoryWatcherTest {
    @TempDir Path directory;

    @Test void lastSubscriberCanLeaveOnEdtWhileRebindingAndTheStaleKeyIsCancelled() throws Exception {
        try (var held = new HeldRebind(Files.createDirectory(directory.resolve("held")))) {
            WatchKey key = held.registered.get(5, TimeUnit.SECONDS);
            var left = new CompletableFuture<Void>();
            SwingUtilities.invokeLater(() -> {
                try { held.stop.run(); left.complete(null); }
                catch (Throwable failure) { left.completeExceptionally(failure); }
            });
            left.get(2, TimeUnit.SECONDS);
            assertFalse(held.release.isDone(), "Unsubscribe must finish before native registration is released");
            held.release.complete(null);
            FileUtils.withPausedDirectoryWatchers(List.of(directory), () -> assertFalse(key.isValid(),
                    "A registration whose final subscriber left must not install a live key"));
        }
    }

    @Test void renamePauseDrainsAnInFlightRebindBeforeMutatingTheDirectory() throws Exception {
        Path folder = Files.createDirectory(directory.resolve("./held"));
        Path watched = folder.toRealPath();
        try (var held = new HeldRebind(folder)) {
            WatchKey key = held.registered.get(5, TimeUnit.SECONDS);
            var moved = new AtomicBoolean();
            var pause = CompletableFuture.runAsync(() -> {
                try {
                    FileUtils.withPausedDirectoryWatchers(List.of(folder), () -> {
                        assertFalse(key.isValid());
                        Files.move(folder, directory.resolve("moved"));
                        moved.set(true);
                    });
                } catch (IOException failure) { throw new AssertionError(failure); }
            });
            var pausedField = FileUtils.class.getDeclaredField("pausedRoots");
            pausedField.setAccessible(true);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean paused;
            do {
                synchronized (FileUtils.class) { paused = ((Map<?, ?>) pausedField.get(null)).containsKey(watched); }
                if (!paused) Thread.sleep(5);
            } while (!paused && System.nanoTime() < deadline);
            assertTrue(paused);
            assertFalse(moved.get(), "The mutation must await cancellation of the in-flight watch handle");
            held.release.complete(null);
            pause.get(5, TimeUnit.SECONDS);
            assertTrue(Files.isDirectory(directory.resolve("moved")));
        }
    }

    /** Hold the provider's second successful register call with a real native key already allocated. */
    private static final class HeldRebind implements AutoCloseable {
        final CompletableFuture<WatchKey> registered = new CompletableFuture<>();
        final CompletableFuture<Void> release = new CompletableFuture<>();
        final Runnable stop;

        HeldRebind(Path folder) throws Exception {
            Path real = folder.toRealPath();
            var calls = new AtomicInteger();
            stop = FileUtils.startNewDirectoryWatcher(real, () -> { }, (path, service) -> {
                WatchKey key = path.register(service, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_DELETE);
                if (calls.incrementAndGet() > 1) {
                    registered.complete(key);
                    try { release.get(10, TimeUnit.SECONDS); }
                    catch (Exception failure) { key.cancel(); throw new IOException(failure); }
                }
                return key;
            });
            Files.delete(real);
            Files.createDirectory(real);
        }

        @Override public void close() {
            release.complete(null);
            stop.run();
        }
    }

    @Test void existingSubscriberRecoversAfterItsDirectoryIsDeletedAndRecreated() throws Exception {
        Path folder = Files.createDirectory(directory.resolve("scripts"));
        var changed = new AtomicReference<>(new CountDownLatch(1));
        Runnable stop = FileUtils.startNewDirectoryWatcher(folder, () -> changed.get().countDown());
        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                changed.set(new CountDownLatch(1));
                Files.delete(folder);
                assertTrue(changed.get().await(5, TimeUnit.SECONDS), "Deletion must invalidate the old watch");
                changed.set(new CountDownLatch(1));
                Files.createDirectory(folder);
                assertTrue(changed.get().await(5, TimeUnit.SECONDS), "The existing subscriber must be notified when watching resumes");
                changed.set(new CountDownLatch(1));
                Path created = Files.writeString(folder.resolve("new.tdscript"), "return 1;");
                assertTrue(changed.get().await(5, TimeUnit.SECONDS), "The replacement directory must deliver subsequent changes");
                changed.set(new CountDownLatch(1));
                Files.delete(created);
                assertTrue(changed.get().await(5, TimeUnit.SECONDS));
            }
        } finally { stop.run(); }
    }

    @Test void aFailedOperationRestoresWatchingAndRefreshesChangesMadeWhilePaused() throws Exception {
        Path folder = Files.createDirectory(directory.resolve("folder"));
        var changed = new AtomicReference<>(new CountDownLatch(1));
        Runnable stop = FileUtils.startNewDirectoryWatcher(folder, () -> changed.get().countDown());
        try {
            assertThrows(IOException.class, () -> FileUtils.withPausedDirectoryWatchers(List.of(folder), () -> {
                Files.writeString(folder.resolve("during"), "");
                throw new IOException("Operation failed");
            }));
            assertTrue(changed.get().await(5, TimeUnit.SECONDS));
            changed.set(new CountDownLatch(1));
            Files.writeString(folder.resolve("after"), "");
            assertTrue(changed.get().await(5, TimeUnit.SECONDS));
        } finally { stop.run(); }
    }

    @Test void nestedScopesAndNewSubscriptionsDoNotReopenHandlesDuringMove() throws Exception {
        Path parent = Files.createDirectories(directory.resolve("parent/child")).getParent();
        Path child = parent.resolve("child");
        Runnable first = FileUtils.startNewDirectoryWatcher(child, () -> {});
        var second = new AtomicReference<Runnable>(() -> {});
        try {
            FileUtils.withPausedDirectoryWatchers(List.of(parent), () -> {
                FileUtils.withPausedDirectoryWatchers(List.of(parent), () ->
                        second.set(FileUtils.startNewDirectoryWatcher(child, () -> {})));
                Files.move(parent, directory.resolve("moved"));
            });
            assertTrue(Files.isDirectory(directory.resolve("moved/child")));
        } finally { first.run(); second.get().run(); }
    }

    @Test void aReplacementDirectoryGetsANewWatchEvenWhileAnOldSubscriberRemains() throws Exception {
        Path folder = Files.createDirectory(directory.resolve("replaced"));
        Runnable old = FileUtils.startNewDirectoryWatcher(folder, () -> {});
        Runnable replacement = () -> {};
        try {
            Files.delete(folder);
            Files.createDirectory(folder);
            var changed = new CountDownLatch(1);
            replacement = FileUtils.startNewDirectoryWatcher(folder, changed::countDown);
            old.run();
            Files.writeString(folder.resolve("new"), "");
            assertTrue(changed.await(5, TimeUnit.SECONDS));
        } finally { old.run(); replacement.run(); }
    }

    @Test void aliasesShareOwnershipAcrossUnsubscribeAndPause() throws Exception {
        Path real = Files.createDirectory(directory.resolve("real"));
        Path alias = directory.resolve("alias");
        if (System.getProperty("os.name").startsWith("Windows")) {
            var process = new ProcessBuilder("cmd", "/c", "mklink", "/J", alias.toString(), real.toString()).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes());
            assertEquals(0, process.waitFor(), output);
        } else Files.createSymbolicLink(alias, real);
        var changed = new AtomicReference<>(new CountDownLatch(1));
        Runnable first = FileUtils.startNewDirectoryWatcher(real, () -> {});
        Runnable second = FileUtils.startNewDirectoryWatcher(alias, () -> changed.get().countDown());
        try {
            first.run();
            Files.writeString(real.resolve("before"), "");
            assertTrue(changed.get().await(5, TimeUnit.SECONDS));
            FileUtils.withPausedDirectoryWatchers(List.of(alias), () -> Files.writeString(real.resolve("during"), ""));
            changed.set(new CountDownLatch(1));
            Files.writeString(real.resolve("after"), "");
            assertTrue(changed.get().await(5, TimeUnit.SECONDS));
        } finally { first.run(); second.run(); Files.delete(alias); }
    }

    @Test void unrelatedFoldersKeepReceivingChangesDuringAnOperation() throws Exception {
        Path other = Files.createDirectory(directory.resolve("other"));
        var changed = new CountDownLatch(1);
        Runnable stop = FileUtils.startNewDirectoryWatcher(other, changed::countDown);
        try {
            FileUtils.withPausedDirectoryWatchers(List.of(Files.createDirectory(directory.resolve("affected"))), () -> {
                Files.writeString(other.resolve("new"), "");
                try { assertTrue(changed.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException failure) { throw new IOException(failure); }
            });
        } finally { stop.run(); }
    }
}
