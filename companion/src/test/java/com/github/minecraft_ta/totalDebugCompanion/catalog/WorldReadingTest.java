package com.github.minecraft_ta.totalDebugCompanion.catalog;

import com.github.minecraft_ta.totalDebugCompanion.game.GameLocation;
import com.github.minecraft_ta.totaldebug.protocol.message.PlayingPayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The current world, read by its one owner, which the World page and the Project tree show. */
class WorldReadingTest {
    @TempDir Path directory;

    @Test
    void theWorldPlayedLastIsReadAgainWhenTheGameSavesIt() throws Exception {
        Path world = LevelDatFixture.write(this.directory.resolve("saves/World"), LevelDatFixture.world("World")).getParent();
        AtomicInteger told = new AtomicInteger();
        try (WorldReading reading = new WorldReading(new GameLocation(this.directory))) {
            reading.changed().subscribe(told::incrementAndGet);
            assertEquals("World", reading.value().saved().name());
            assertEquals(3, reading.value().saved().gameRules().size());

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
        try (WorldReading reading = new WorldReading(location)) {
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
        try (WorldReading reading = new WorldReading(new GameLocation(this.directory))) {
            assertEquals("No world has been played in this instance yet.", reading.value().problem());
        }
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.getAsBoolean(), "timed out");
    }
}
