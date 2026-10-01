package com.github.minecraft_ta.totalDebugCompanion.catalog;

import com.github.minecraft_ta.totalDebugCompanion.game.GameLocation;
import com.github.minecraft_ta.totalDebugCompanion.pack.GamePacks;
import com.github.minecraft_ta.totalDebugCompanion.util.WindowFocus;
import com.github.minecraft_ta.totaldebug.protocol.message.PlayingPayload;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The current world, read by its one owner, which the World page and the Project tree show. */
class WorldReadingTest {
    @TempDir Path directory;

    @Test
    void aWorldTheGameSavedIsReadAgainWhenTheUserComesBack() throws Exception {
        Path world = LevelDatFixture.write(this.directory.resolve("saves/World"), LevelDatFixture.world("World")).getParent();
        GameLocation location = new GameLocation(this.directory);
        AtomicInteger told = new AtomicInteger();
        try (WorldReading reading = new WorldReading(location, new GamePacks(location))) {
            location.connected(message -> true);
            location.playing(new PlayingPayload.Singleplayer(world.toString()));
            await(() -> reading.value().saved().open());
            reading.changed().subscribe(told::incrementAndGet);

            // The game saves the world with a rule changed while the user plays.
            Map<String, Object> saved = LevelDatFixture.world("World");
            ((Map<String, Object>) saved.get("GameRules")).put("keepInventory", "false");
            LevelDatFixture.write(world, saved);
            Thread.sleep(300);
            assertEquals(0, told.get(), "nothing watches the world");

            WindowFocus.returned().fire();
            await(() -> told.get() == 1);
            assertEquals("false", reading.value().saved().gameRules().get("keepInventory"));
            WindowFocus.returned().fire();
            Thread.sleep(300);
            assertEquals(1, told.get(), "a world read again unchanged tells nothing");
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

            LevelDatFixture.write(first, LevelDatFixture.world("First renamed"));
            WindowFocus.returned().fire();
            Thread.sleep(300);
            assertEquals(before, told.get(), "the world followed before tells nothing any more");
        }
    }

    @Test
    void whenTheGamePlaysAnotherWorldItsFollowersFindItsNamePublished() throws Exception {
        Path first = LevelDatFixture.write(this.directory.resolve("saves/First"), LevelDatFixture.world("First")).getParent();
        Path second = LevelDatFixture.write(this.directory.resolve("saves/Second"), LevelDatFixture.world("Second")).getParent();
        GameLocation location = new GameLocation(this.directory);
        List<String> named = new CopyOnWriteArrayList<>();
        try (WorldReading reading = new WorldReading(location, new GamePacks(location))) {
            location.connected(message -> true);
            location.playing(new PlayingPayload.Singleplayer(first.toString()));
            await(() -> first.equals(reading.value().directory()));
            reading.changed().subscribe(() -> named.add(reading.publishedName().orElse("")));

            location.playing(new PlayingPayload.Singleplayer(second.toString()));
            await(() -> named.contains("Second"));
            assertFalse(named.contains(""), "no follower is told before the world it is told of was read: " + named);
        }
    }

    @Test
    void withoutAWorldThereIsNone() throws Exception {
        try (WorldReading reading = reading()) {
            assertNull(reading.value().directory());
        }
    }

    @Test
    void aReadThatFailsKeepsTheWorldReadBeforeAndTellsNothing() throws Exception {
        Path world = LevelDatFixture.write(this.directory.resolve("saves/World"), LevelDatFixture.world("World")).getParent();
        AtomicInteger told = new AtomicInteger();
        try (WorldReading reading = reading()) {
            WorldReading.World before = reading.value();
            reading.changed().subscribe(told::incrementAndGet);

            // Caught while the game writes level.dat: the World page keeps the world and the datapacks staged for it.
            Files.write(world.resolve("level.dat"), new byte[]{1, 2, 3});
            WindowFocus.returned().fire();
            Thread.sleep(300);
            assertEquals(0, told.get());
            assertEquals(before, reading.value());

            LevelDatFixture.write(world, LevelDatFixture.world("World"));
            WindowFocus.returned().fire();
            await(() -> told.get() == 1);
            assertEquals("World", reading.value().saved().name());
        }
    }

    @Test
    void aWorldTheConnectedGameLeftCanBeMoved() throws Exception {
        Path world = LevelDatFixture.write(this.directory.resolve("saves/World"), LevelDatFixture.world("World")).getParent();
        LevelDatFixture.datapack(world, "Pack");
        GameLocation location = new GameLocation(this.directory);
        try (WorldReading reading = new WorldReading(location, new GamePacks(location))) {
            location.connected(message -> true);
            location.playing(new PlayingPayload.Singleplayer(world.toString()));
            await(() -> reading.value().saved().open());

            // The game tells it is in the menu once it let go of the world; its Delete World or a backup moves it.
            location.playing(new PlayingPayload.Menu());
            await(() -> !reading.value().saved().open());
            Files.move(world, this.directory.resolve("saves/Moved"));
            assertFalse(Files.exists(world), "nothing of Companion holds the folder");
        }
    }

    @Test
    void aNewWorldAGameCompanionIsNotConnectedToOpensIsReadWhenTheUserComesBack() throws Exception {
        Path first = LevelDatFixture.write(this.directory.resolve("saves/First"), LevelDatFixture.world("First")).getParent();
        try (WorldReading reading = reading()) {
            assertEquals(first, reading.value().directory());

            // A game Companion is not connected to creates a world and opens it.
            Path second = Files.createDirectories(this.directory.resolve("saves/Second"));
            try (LevelDatFixture.Held game = LevelDatFixture.hold(second)) {
                LevelDatFixture.write(second, LevelDatFixture.world("Second"));
                WindowFocus.returned().fire();
                await(() -> second.equals(reading.value().directory()));
                assertTrue(reading.value().saved().open(), "the world the game holds is read as open");
            }
        }
    }

    @Test
    void theWorldPlayedBeforeIsCurrentOnceTheCurrentOneIsDeleted() throws Exception {
        Path first = LevelDatFixture.write(this.directory.resolve("saves/First"), LevelDatFixture.world("First")).getParent();
        Path second = LevelDatFixture.write(this.directory.resolve("saves/Second"), LevelDatFixture.world("Second")).getParent();
        Files.setLastModifiedTime(first.resolve("level.dat"), FileTime.fromMillis(System.currentTimeMillis() - 60_000));
        try (WorldReading reading = reading()) {
            assertEquals(second, reading.value().directory());

            // The game's Delete World removes the world played last.
            try (Stream<Path> files = Files.walk(second)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
            }
            WindowFocus.returned().fire();
            await(() -> first.equals(reading.value().directory()));
        }
    }

    @Test
    void aDatapackAddedToTheWorldIsAChange() throws Exception {
        Path world = LevelDatFixture.write(this.directory.resolve("saves/World"), LevelDatFixture.world("World")).getParent();
        AtomicInteger told = new AtomicInteger();
        try (WorldReading reading = reading()) {
            reading.value();
            reading.changed().subscribe(told::incrementAndGet);

            LevelDatFixture.datapack(world, "Added");
            WindowFocus.returned().fire();
            await(() -> told.get() == 1);
        }
    }

    @Test
    void aNewIconOfTheWorldIsAChange() throws Exception {
        Path world = LevelDatFixture.write(this.directory.resolve("saves/World"), LevelDatFixture.world("World")).getParent();
        AtomicInteger told = new AtomicInteger();
        try (WorldReading reading = reading()) {
            reading.value();
            reading.changed().subscribe(told::incrementAndGet);

            Files.write(world.resolve("icon.png"), new byte[]{1, 2, 3});
            WindowFocus.returned().fire();
            await(() -> told.get() == 1);
        }
    }

    @Test
    void anotherWorldThatCannotBeReadIsCurrentWithoutWhatItHolds() throws Exception {
        Path first = LevelDatFixture.write(this.directory.resolve("saves/First"), LevelDatFixture.world("First")).getParent();
        Path second = LevelDatFixture.write(this.directory.resolve("saves/Second"), LevelDatFixture.world("Second")).getParent();
        Files.write(second.resolve("level.dat"), new byte[]{1, 2, 3});
        GameLocation location = new GameLocation(this.directory);
        try (WorldReading reading = new WorldReading(location, new GamePacks(location))) {
            location.connected(message -> true);
            location.playing(new PlayingPayload.Singleplayer(first.toString()));
            await(() -> first.equals(reading.value().directory()) && reading.value().saved() != null);

            // The game opens the second world while its level.dat is being written.
            location.playing(new PlayingPayload.Singleplayer(second.toString()));
            await(() -> second.equals(reading.value().directory()));
            assertNull(reading.value().saved(), "the world before is not shown or changed as if it were current");
        }
    }

    /** The current world of a game Companion is not connected to. */
    private WorldReading reading() {
        GameLocation location = new GameLocation(this.directory);
        return new WorldReading(location, new GamePacks(location));
    }

    /** A condition on what the reading holds, which reads it. */
    @FunctionalInterface
    private interface Condition {
        boolean holds() throws Exception;
    }

    private static void await(Condition condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.holds() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(condition.holds(), "timed out");
    }
}
