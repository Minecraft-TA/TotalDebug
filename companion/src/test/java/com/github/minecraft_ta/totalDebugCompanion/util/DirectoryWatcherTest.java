package com.github.minecraft_ta.totalDebugCompanion.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class DirectoryWatcherTest {
    @TempDir Path directory;

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
