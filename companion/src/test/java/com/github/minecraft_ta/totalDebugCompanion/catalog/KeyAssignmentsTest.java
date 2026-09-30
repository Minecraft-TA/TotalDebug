package com.github.minecraft_ta.totalDebugCompanion.catalog;

import java.nio.file.AccessDeniedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
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
            assignments.changed().subscribe(told::incrementAndGet);
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
    void aGameFolderThatAppearsLaterIsWatchedOnceItDoes() throws Exception {
        Path options = this.directory.resolve("game/options.txt");
        AtomicInteger told = new AtomicInteger();
        try (KeyAssignments assignments = new KeyAssignments(options)) {
            assignments.changed().subscribe(told::incrementAndGet);
            Thread.sleep(300);

            // The game runs for the first time: its folder and options.txt appear.
            Files.createDirectories(options.getParent());
            Files.writeString(options, "key_key.jump:key.keyboard.g\n");
            assertEquals(Map.of("key.jump", KeyBindings.Assignment.decode("key.keyboard.g")), assignments.assignments(),
                    "until the folder is watched, the keys are read whenever asked");
            await(told::get, 1);

            replace(options, "key_key.jump:key.keyboard.h\n");
            await(told::get, 2);
            assertEquals(Map.of("key.jump", KeyBindings.Assignment.decode("key.keyboard.h")), assignments.assignments(),
                    "watched now, a key rebound in the game is told without Companion asking");
        }
    }

    @Test
    void aPageThatReadBeforeTheOwnerStillHearsOfTheNextChange() throws Exception {
        Path options = this.directory.resolve("options.txt");
        Files.writeString(options, "key_key.jump:key.keyboard.space" + System.lineSeparator());
        AtomicInteger told = new AtomicInteger();
        try (KeyAssignments assignments = new KeyAssignments(options)) {
            assignments.changed().subscribe(told::incrementAndGet);
            // A page opened at once reads the keys, perhaps before the owner's own first read, and the game then saves.
            Map<String, KeyBindings.Assignment> shown = assignments.assignments();
            replace(options, "key_key.jump:key.keyboard.g" + System.lineSeparator());
            await(told::get, 1);
            assertEquals(Map.of("key.jump", KeyBindings.Assignment.decode("key.keyboard.space")), shown);
        }
    }

    /**
     * Writes {@code text} beside {@code file} and moves it over the file, as an editor may save it. Windows refuses the move
     * while the file is being read, as by the watch itself, so it is tried again for a moment.
     */
    private static void replace(Path file, String text) throws Exception {
        Path staged = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(staged, text);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (true) {
            try {
                Files.move(staged, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return;
            } catch (AccessDeniedException reading) {
                if (System.nanoTime() > deadline) throw reading;
                Thread.sleep(20);
            }
        }
    }

    private static void await(IntSupplier count, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (count.getAsInt() < expected && System.nanoTime() < deadline) Thread.sleep(20);
        assertEquals(expected, count.getAsInt());
    }
}
