package com.github.minecraft_ta.totalDebugCompanion.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LevelDatTest {
    @TempDir Path directory;

    @Test
    void anUpdateIsWrittenAsTheGameSavesAndReleasesTheWorld() throws Exception {
        Path world = this.directory.resolve("saves/World");
        LevelDatFixture.write(world, LevelDatFixture.world("World"));

        LevelDat.update(world, read -> read);

        assertTrue(Files.isRegularFile(world.resolve("level.dat_old")), "the last level.dat is kept, as the game keeps it");
        assertFalse(Worlds.isOpen(world), "the world's lock is released once written");
    }

    @Test
    void aWorldSomethingHasOpenIsNotWritten() throws Exception {
        Path world = this.directory.resolve("saves/World");
        LevelDatFixture.write(world, LevelDatFixture.world("World"));
        byte[] before = Files.readAllBytes(world.resolve("level.dat"));

        try (FileChannel channel = FileChannel.open(world.resolve("session.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            IOException refused = assertThrows(IOException.class, () -> LevelDat.update(world, read -> read));
            assertEquals("The world World is open, and its level.dat is written by what has it open", refused.getMessage());
        }
        assertEquals(before.length, Files.readAllBytes(world.resolve("level.dat")).length);
        assertFalse(Files.exists(world.resolve("level.dat_old")));
    }
}
