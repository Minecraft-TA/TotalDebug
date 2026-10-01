package com.github.minecraft_ta.totalDebugCompanion.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The watch of files Companion handed to another program. */
class FileWatchTest {
    @TempDir Path directory;

    @Test
    void aWriteOfTheFileIsToldAndOneOfItsNeighbourIsNot() throws Exception {
        Path file = Files.writeString(this.directory.resolve("stone.png"), "one");
        AtomicInteger told = new AtomicInteger();
        Runnable stop = FileWatch.shared().watch(file, told::incrementAndGet);
        try {
            Files.writeString(this.directory.resolve("dirt.png"), "other");
            Thread.sleep(300);
            assertEquals(0, told.get(), "another file of the folder tells nothing");

            Files.writeString(file, "two");
            await(() -> told.get() > 0);
        } finally {
            stop.run();
        }
        int before = told.get();
        Files.writeString(file, "three");
        Thread.sleep(300);
        assertEquals(before, told.get(), "a stopped watch tells nothing");
    }

    @Test
    void twoFilesOfOneFolderAreToldApartAndOneStoppedLeavesTheOther() throws Exception {
        Path first = Files.writeString(this.directory.resolve("first.png"), "one");
        Path second = Files.writeString(this.directory.resolve("second.png"), "one");
        AtomicInteger firstTold = new AtomicInteger();
        AtomicInteger secondTold = new AtomicInteger();
        Runnable stopFirst = FileWatch.shared().watch(first, firstTold::incrementAndGet);
        Runnable stopSecond = FileWatch.shared().watch(second, secondTold::incrementAndGet);
        try {
            stopFirst.run();
            Files.writeString(second, "two");
            await(() -> secondTold.get() > 0);
            assertEquals(0, firstTold.get());
        } finally {
            stopSecond.run();
        }
    }

    @Test
    void aRemovedFileIsTold() throws Exception {
        Path file = Files.writeString(this.directory.resolve("stone.png"), "one");
        AtomicInteger told = new AtomicInteger();
        Runnable stop = FileWatch.shared().watch(file, told::incrementAndGet);
        try {
            // As between an editor's delete and rename.
            Files.delete(file);
            await(() -> told.get() > 0);
        } finally {
            stop.run();
        }
    }

    @Test
    void aFileWhoseFolderIsMissingCannotBeWatched() {
        assertThrows(IOException.class, () -> FileWatch.shared().watch(this.directory.resolve("missing/stone.png"), () -> { }));
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "timed out");
    }
}
