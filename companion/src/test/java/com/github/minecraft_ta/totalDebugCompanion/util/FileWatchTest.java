package com.github.minecraft_ta.totalDebugCompanion.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The one watcher: listings told of entries only, and Companion's own moves of watched folders. */
class FileWatchTest {
    @TempDir Path directory;

    @Test
    void aWatchedFolderAndOneInsideItCanBeMovedWhileTheWatchIsPaused() throws Exception {
        Path parent = Files.createDirectories(this.directory.resolve("parent/child")).getParent();
        Runnable onParent = FileWatch.shared().watchEntries(parent, () -> { });
        Runnable onChild = FileWatch.shared().watchEntries(parent.resolve("child"), () -> { });
        try {
            FileWatch.shared().pausing(List.of(parent), () -> Files.move(parent, this.directory.resolve("moved")));
            assertTrue(Files.isDirectory(this.directory.resolve("moved/child")));
        } finally {
            onParent.run();
            onChild.run();
        }
    }

    @Test
    void aFollowerAddedWhileAPauseLastsWatchesNothingThereUntilItEnds() throws Exception {
        Path parent = Files.createDirectories(this.directory.resolve("parent/child")).getParent();
        AtomicReference<Runnable> added = new AtomicReference<>(() -> { });
        try {
            FileWatch.shared().pausing(List.of(parent), () -> {
                FileWatch.shared().pausing(List.of(parent), () -> added.set(FileWatch.shared().watchEntries(parent.resolve("child"), () -> { })));
                Files.move(parent, this.directory.resolve("moved"));
            });
            assertTrue(Files.isDirectory(this.directory.resolve("moved/child")));
        } finally {
            added.get().run();
        }
    }

    @Test
    void aFailedOperationWatchesAgainAndTellsWhatItChanged() throws Exception {
        Path folder = Files.createDirectory(this.directory.resolve("folder"));
        AtomicReference<CountDownLatch> changed = new AtomicReference<>(new CountDownLatch(1));
        Runnable stop = FileWatch.shared().watchEntries(folder, () -> changed.get().countDown());
        try {
            assertThrows(IOException.class, () -> FileWatch.shared().pausing(List.of(folder), () -> {
                Files.writeString(folder.resolve("during"), "");
                throw new IOException("the operation failed");
            }));
            assertTrue(changed.get().await(5, TimeUnit.SECONDS), "what the operation changed is told");
            changed.set(new CountDownLatch(1));
            Files.writeString(folder.resolve("after"), "");
            assertTrue(changed.get().await(5, TimeUnit.SECONDS), "the folder is watched again");
        } finally {
            stop.run();
        }
    }

    @Test
    void otherFoldersAreToldWhileAPauseLasts() throws Exception {
        Path other = Files.createDirectory(this.directory.resolve("other"));
        CountDownLatch changed = new CountDownLatch(1);
        Runnable stop = FileWatch.shared().watchEntries(other, changed::countDown);
        try {
            FileWatch.shared().pausing(List.of(Files.createDirectory(this.directory.resolve("affected"))), () -> {
                Files.writeString(other.resolve("new"), "");
                try {
                    assertTrue(changed.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    throw new IOException(interrupted);
                }
            });
        } finally {
            stop.run();
        }
    }

    @Test
    void aFolderReachedThroughALinkIsPausedAndWatchedAgain() throws Exception {
        Path real = Files.createDirectory(this.directory.resolve("real"));
        Path alias = this.directory.resolve("alias");
        Process link = new ProcessBuilder("cmd", "/c", "mklink", "/J", alias.toString(), real.toString()).redirectErrorStream(true).start();
        String output = new String(link.getInputStream().readAllBytes());
        assertEquals(0, link.waitFor(), output);
        AtomicReference<CountDownLatch> changed = new AtomicReference<>(new CountDownLatch(1));
        Runnable first = FileWatch.shared().watchEntries(real, () -> { });
        Runnable second = FileWatch.shared().watchEntries(alias, () -> changed.get().countDown());
        try {
            first.run();
            Files.writeString(real.resolve("before"), "");
            assertTrue(changed.get().await(5, TimeUnit.SECONDS), "the link's follower keeps the shared watch");
            FileWatch.shared().pausing(List.of(alias), () -> Files.writeString(real.resolve("during"), ""));
            changed.set(new CountDownLatch(1));
            Files.writeString(real.resolve("after"), "");
            assertTrue(changed.get().await(5, TimeUnit.SECONDS), "watched again after the pause");
        } finally {
            first.run();
            second.run();
            Files.delete(alias);
        }
    }

    @Test
    void aListingIsToldOfEntriesCreatedAndRemovedButNotOfWrites() throws Exception {
        Path folder = Files.createDirectory(this.directory.resolve("scripts"));
        Path file = Files.writeString(folder.resolve("existing.tdscript"), "return 1;");
        AtomicInteger told = new AtomicInteger();
        Runnable stop = FileWatch.shared().watchEntries(folder, told::incrementAndGet);
        try {
            Thread.sleep(300);
            Files.writeString(file, "return 2;");
            Thread.sleep(700);
            assertEquals(0, told.get(), "a write of an entry is not a change of the listing");
            Path created = Files.writeString(folder.resolve("new.tdscript"), "return 3;");
            await(() -> told.get() >= 1);
            int afterCreate = told.get();
            Files.delete(created);
            await(() -> told.get() > afterCreate);
        } finally {
            stop.run();
        }
    }

    @Test
    void aListingOfAFolderDeletedAndMadeAgainIsToldAndWatchedAgain() throws Exception {
        Path folder = Files.createDirectory(this.directory.resolve("scripts"));
        AtomicReference<CountDownLatch> changed = new AtomicReference<>(new CountDownLatch(1));
        Runnable stop = FileWatch.shared().watchEntries(folder, () -> changed.get().countDown());
        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                changed.set(new CountDownLatch(1));
                Files.delete(folder);
                assertTrue(changed.get().await(5, TimeUnit.SECONDS), "the folder's removal is told");
                changed.set(new CountDownLatch(1));
                Files.createDirectory(folder);
                assertTrue(changed.get().await(5, TimeUnit.SECONDS), "the folder made again is told");
                changed.set(new CountDownLatch(1));
                Path created = Files.writeString(folder.resolve("new.tdscript"), "return 1;");
                assertTrue(changed.get().await(5, TimeUnit.SECONDS), "the folder made again is watched");
                changed.set(new CountDownLatch(1));
                Files.delete(created);
                assertTrue(changed.get().await(5, TimeUnit.SECONDS));
            }
        } finally {
            stop.run();
        }
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "timed out");
    }
}
