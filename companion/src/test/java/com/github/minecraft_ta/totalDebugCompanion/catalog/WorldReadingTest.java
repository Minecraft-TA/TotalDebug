package com.github.minecraft_ta.totalDebugCompanion.catalog;

import com.github.minecraft_ta.totalDebugCompanion.game.GameLocation;
import com.github.minecraft_ta.totaldebug.protocol.message.PlayingPayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.minecraft_ta.totalDebugCompanion.pack.GamePacks;
import java.util.stream.Stream;
import java.util.Comparator;
import java.nio.file.attribute.FileTime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The current world, read by its one owner, which the World page and the Project tree show. */
class WorldReadingTest {
    @TempDir Path directory;

    @Test
    void theWorldTheGameHoldsIsReadAgainWhenTheGameSavesIt() throws Exception {
        Path world = LevelDatFixture.write(this.directory.resolve("saves/World"), LevelDatFixture.world("World")).getParent();
        AtomicInteger told = new AtomicInteger();
        try (LevelDatFixture.Held game = LevelDatFixture.hold(world); WorldReading reading = reading()) {
            reading.changed().subscribe(told::incrementAndGet);
            assertEquals("World", reading.value().saved().name());
            assertTrue(reading.value().saved().open());
            Thread.sleep(300);

            // The game saves the world with a rule changed.
            Map<String, Object> saved = LevelDatFixture.world("World");
            ((Map<String, Object>) saved.get("GameRules")).put("keepInventory", "false");
            LevelDatFixture.write(world, saved);
            await(() -> told.get() >= 1);
            assertEquals("false", reading.value().saved().gameRules().get("keepInventory"));
        }
    }

    @Test
    void theWorldTheGamePlaysIsFollowedAndTheOneBeforeTellsNothing() throws Exception {
        Path first = LevelDatFixture.write(this.directory.resolve("saves/First"), LevelDatFixture.world("First")).getParent();
        Path second = LevelDatFixture.write(this.directory.resolve("saves/Second"), LevelDatFixture.world("Second")).getParent();
        Files.setLastModifiedTime(second.resolve("level.dat"), Files.getLastModifiedTime(first.resolve("level.dat")));
        GameLocation location = new GameLocation(this.directory);
        AtomicInteger told = new AtomicInteger();
        try (WorldReading reading = new WorldReading(location, new GamePacks(location))) {
            reading.changed().subscribe(told::incrementAndGet);
            reading.value();

            // The game connects and opens the second world.
            location.connected(message -> true);
            location.playing(new PlayingPayload.Singleplayer(second.toString()));
            await(() -> second.equals(reading.value().directory()));
            assertEquals("Second", reading.value().saved().name());
            assertTrue(reading.value().saved().open(), "the world the game has open is read as open");
            int before = told.get();

            Map<String, Object> other = LevelDatFixture.world("First renamed");
            LevelDatFixture.write(first, other);
            Thread.sleep(1_000);
            assertEquals(before, told.get(), "the world followed before tells nothing any more");
        }
    }

    @Test
    void withoutAWorldThePageIsToldWhy() {
        try (WorldReading reading = reading()) {
            assertEquals("No world has been played in this instance yet.", reading.value().problem());
        }
    }

    @Test
    void aWorldTheGameDoesNotHoldCanBeDeletedWhileItIsShown() throws Exception {
        Path world = LevelDatFixture.write(this.directory.resolve("saves/World"), LevelDatFixture.world("World")).getParent();
        GameLocation location = new GameLocation(this.directory);
        try (WorldReading reading = new WorldReading(location, new GamePacks(location))) {
            assertEquals("World", reading.value().saved().name());
            Thread.sleep(300);
            // As the game's Delete World removes a world it does not hold.
            try (Stream<Path> files = Files.walk(world)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
            }
            assertFalse(Files.exists(world), "nothing of Companion holds the folder");
        }
    }

    @Test
    void aNewWorldAGameCompanionIsNotConnectedToOpensIsFollowed() throws Exception {
        Path first = LevelDatFixture.write(this.directory.resolve("saves/First"), LevelDatFixture.world("First")).getParent();
        try (WorldReading reading = reading()) {
            assertEquals(first, reading.value().directory());
            Thread.sleep(300);

            // A game Companion is not connected to creates a world and opens it.
            Path second = Files.createDirectories(this.directory.resolve("saves/Second"));
            try (LevelDatFixture.Held game = LevelDatFixture.hold(second)) {
                LevelDatFixture.write(second, LevelDatFixture.world("Second"));
                await(() -> second.equals(reading.value().directory()));
                assertTrue(reading.value().saved().open(), "the world the game holds is read as open");
            }
        }
    }

    @Test
    void theWorldPlayedBeforeIsFollowedWhenTheCurrentOneIsDeleted() throws Exception {
        Path first = LevelDatFixture.write(this.directory.resolve("saves/First"), LevelDatFixture.world("First")).getParent();
        Path second = LevelDatFixture.write(this.directory.resolve("saves/Second"), LevelDatFixture.world("Second")).getParent();
        Files.setLastModifiedTime(first.resolve("level.dat"), FileTime.fromMillis(System.currentTimeMillis() - 60_000));
        try (WorldReading reading = reading()) {
            assertEquals(second, reading.value().directory());
            Thread.sleep(300);

            // The game's Delete World removes the world played last.
            try (Stream<Path> files = Files.walk(second)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
            }
            await(() -> first.equals(reading.value().directory()));
        }
    }

    @Test
    void aNewIconOrDatapackOfTheWorldTheGameHoldsIsAChange() throws Exception {
        Path world = LevelDatFixture.write(this.directory.resolve("saves/World"), LevelDatFixture.world("World")).getParent();
        GameLocation location = new GameLocation(this.directory);
        AtomicInteger told = new AtomicInteger();
        try (WorldReading reading = new WorldReading(location, new GamePacks(location))) {
            location.connected(message -> true);
            location.playing(new PlayingPayload.Singleplayer(world.toString()));
            await(() -> reading.value().saved() != null && reading.value().saved().open());
            reading.changed().subscribe(told::incrementAndGet);
            Thread.sleep(700);
            int before = told.get();

            Files.write(world.resolve("icon.png"), new byte[]{1, 2, 3});
            await(() -> told.get() > before);
            int afterIcon = told.get();
            LevelDatFixture.datapack(world, "Added");
            await(() -> told.get() > afterIcon);
        }
    }

    /** The current world of a game Companion is not connected to. */
    private WorldReading reading() {
        GameLocation location = new GameLocation(this.directory);
        return new WorldReading(location, new GamePacks(location));
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "timed out");
    }
}
