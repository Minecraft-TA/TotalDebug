package com.github.minecraft_ta.totalDebugCompanion.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurrentWorldTest {
    @TempDir Path directory;

    @Test
    void aWorldReadsAsItsLevelDatSavedIt() throws Exception {
        Path world = this.directory.resolve("saves/New World");
        LevelDatFixture.write(world, LevelDatFixture.world("Test"));

        CurrentWorld.Saved saved = CurrentWorld.read(world);
        assertEquals("Test", saved.name());
        assertFalse(saved.open());
        assertEquals(8_757_790_292_842_126_093L, saved.seed());
        assertEquals("Survival", saved.gameMode());
        assertEquals("Normal", saved.difficulty());
        assertTrue(saved.commands());
        assertEquals(new CurrentWorld.Spawn(21, 77, -28), saved.spawn());
        assertEquals("1.21.1", saved.version());
        assertEquals(Instant.ofEpochMilli(1_790_000_000_000L), saved.lastPlayed());
        assertEquals("Rain", saved.weather());
        assertEquals(List.of("doDaylightCycle", "keepInventory", "randomTickSpeed"), List.copyOf(saved.gameRules().keySet()),
                "rules are listed by name");
    }

    @Test
    void theDayStartsAtSixInTheMorning() throws Exception {
        Path world = this.directory.resolve("world");
        Map<String, Object> data = LevelDatFixture.world("Test");
        data.put("DayTime", 30_000L);
        LevelDatFixture.write(world, data);
        CurrentWorld.Saved saved = CurrentWorld.read(world);
        assertEquals(2, saved.day(), "24000 ticks make a day");
        assertEquals("12:00", saved.timeOfDay());

        data.put("DayTime", 18_500L);
        LevelDatFixture.write(world, data);
        assertEquals("00:30", CurrentWorld.read(world).timeOfDay());
    }

    @Test
    void datapacksFollowThePackScreenWithNewFolderPacksLast() throws Exception {
        Path world = this.directory.resolve("world");
        LevelDatFixture.write(world, LevelDatFixture.world("Test"));
        Path tweaks = LevelDatFixture.datapack(world, "Tweaks");
        Path added = Files.writeString(world.resolve("datapacks/Added.zip"), "");
        Files.writeString(world.resolve("datapacks/notes.txt"), "not a pack");
        Files.createDirectories(world.resolve("datapacks/backup"));
        Files.writeString(world.resolve("datapacks/Old.ZIP"), "");

        List<ListedPack> packs = CurrentWorld.read(world).datapacks();
        assertEquals(List.of("file/Tweaks", "mod_data", "vanilla", "bundle", "mod/testmod:data/testmod/datapacks/extra", "file/Added.zip"),
                packs.stream().map(ListedPack::id).toList(),
                "the highest enabled pack comes first, as in the game, which skips a folder without pack.mcmeta and a .ZIP");
        assertEquals(List.of(ListedPack.State.ENABLED, ListedPack.State.ENABLED, ListedPack.State.ENABLED,
                        ListedPack.State.DISABLED, ListedPack.State.DISABLED, ListedPack.State.NEW),
                packs.stream().map(ListedPack::state).toList(), "the game enables a new folder pack when it loads the world");
        assertEquals(tweaks, packs.getFirst().file());
        assertEquals(added, packs.getLast().file());
        assertNull(packs.get(1).file());
    }

    @Test
    void theCurrentWorldIsTheOnePlayedLastWhileNoneIsOpen() throws Exception {
        Path older = this.directory.resolve("saves/Older");
        Path newer = this.directory.resolve("saves/Newer");
        Files.setLastModifiedTime(LevelDatFixture.write(older, LevelDatFixture.world("Older")), FileTime.fromMillis(1_000));
        Files.setLastModifiedTime(LevelDatFixture.write(newer, LevelDatFixture.world("Newer")), FileTime.fromMillis(2_000));

        assertEquals(newer, CurrentWorld.directory(this.directory).orElseThrow());
        assertTrue(CurrentWorld.directory(this.directory.resolve("empty")).isEmpty());
    }

    @Test
    void whileTheGameSavesTheLastLevelDatIsRead() throws Exception {
        Path world = this.directory.resolve("world");
        Path level = LevelDatFixture.write(world, LevelDatFixture.world("Test"));
        Files.move(level, world.resolve("level.dat_old"));

        assertEquals("Test", CurrentWorld.read(world).name(), "the game renames level.dat to level.dat_old before it writes the new one");
    }

    @Test
    void aFileThatIsNotAWorldSaysSo() throws Exception {
        Path world = Files.createDirectories(this.directory.resolve("world"));
        try (OutputStream output = new GZIPOutputStream(Files.newOutputStream(world.resolve("level.dat")))) {
            output.write(new byte[]{8, 0, 0});
        }
        IOException failure = assertThrows(IOException.class, () -> CurrentWorld.read(world));
        assertTrue(failure.getMessage().endsWith("does not start with a compound"), failure.getMessage());

        Files.write(world.resolve("level.dat"), new byte[]{1, 2, 3});
        assertThrows(IOException.class, () -> CurrentWorld.read(world), "a file that is not gzipped");
    }
}
