package com.github.minecraft_ta.totalDebugCompanion.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KeyAssignmentsTest {
    @TempDir Path directory;

    @Test
    void aKeyReboundIsToldAndAnotherOptionIsNot() throws Exception {
        Path options = this.directory.resolve("options.txt");
        Files.writeString(options, "soundCategory_master:1.0\nkey_key.jump:key.keyboard.space\n");
        AtomicInteger told = new AtomicInteger();
        try (KeyAssignments assignments = new KeyAssignments(options)) {
            assignments.addListener(told::incrementAndGet);
            // The first read only learns what the file assigns.
            Thread.sleep(500);

            replace(options, "soundCategory_master:0.4\nkey_key.jump:key.keyboard.space\n");
            Thread.sleep(1_000);
            assertEquals(0, told.get(), "the volume changed; no key did");

            replace(options, "soundCategory_master:0.4\nkey_key.jump:key.keyboard.g\n");
            await(told::get, 1);
            Thread.sleep(500);
            assertEquals(1, told.get(), "a file written in parts is read once it settled");

            Files.delete(options);
            await(told::get, 2);
        }
    }

    @Test
    void aGameFolderThatCannotBeWatchedTellsNothing() throws Exception {
        try (KeyAssignments assignments = new KeyAssignments(this.directory.resolve("missing/options.txt"))) {
            assignments.addListener(() -> { });
        }
    }

    /** Writes {@code text} beside {@code file} and moves it over the file, as the game and Companion save it. */
    private static void replace(Path file, String text) throws Exception {
        Path staged = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(staged, text);
        Files.move(staged, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void await(IntSupplier count, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (count.getAsInt() < expected && System.nanoTime() < deadline) Thread.sleep(20);
        assertEquals(expected, count.getAsInt());
    }
}
