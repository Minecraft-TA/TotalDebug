package com.github.minecraft_ta.totalDebugCompanion.catalog;

import java.util.ArrayList;
import org.junit.jupiter.api.AfterEach;
import com.github.minecraft_ta.totalDebugCompanion.change.ChangePipeline;
import com.github.minecraft_ta.totalDebugCompanion.change.Effect;
import com.github.minecraft_ta.totalDebugCompanion.game.GameLocation;
import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;
import com.github.minecraft_ta.totaldebug.protocol.message.PlayingPayload;
import com.github.minecraft_ta.totaldebug.storage.GameLock;
import com.github.minecraft_ta.totaldebug.storage.InstancePaths;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigChangesTest {
    @TempDir Path directory;

    private final List<KeyAssignments> assignments = new ArrayList<>();

    @AfterEach
    void closeAssignments() {
        this.assignments.forEach(KeyAssignments::close);
    }

    /** The key assignments of the test's {@code options.txt}, watched until the test ends. */
    private KeyAssignments assignments() {
        KeyAssignments assignments = new KeyAssignments(this.directory.resolve("options.txt"));
        this.assignments.add(assignments);
        return assignments;
    }
    private GameLocation location;

    @BeforeEach
    void location() {
        this.location = new GameLocation(this.directory);
    }

    @Test
    void theFirstGameToConnectKeepsTheEditsMadeWhileItRan() throws Exception {
        ConfigChanges changes = new ConfigChanges(this.location, ChangeRecord.inMemory());
        try (GameLock ignored = GameLock.hold(InstancePaths.forGame(this.directory).gameLock())) {
            edit(changes, config(), PackCatalog.Restart.GAME, "1", "2");
            assertEquals(Effect.RESTART, changes.pending(config(), "speed"));

            this.location.connected(message -> true);
            this.location.process(42);

            assertEquals(Effect.RESTART, changes.pending(config(), "speed"),
                    "the game that runs now is the one the edit waits in");
        }
    }

    @Test
    void aClosedGameUsesEditsWhenItStarts() {
        ConfigChanges changes = new ConfigChanges(this.location, ChangeRecord.inMemory());

        assertEquals(Effect.GAME_STARTS, edit(changes, config(), PackCatalog.Restart.GAME, "1", "2"));
        assertEquals(Effect.WORLD_OPENS, edit(changes, world("World").resolve("serverconfig/testmod-server.toml"),
                PackCatalog.Restart.NONE, "1", "2"));
        assertEquals(Effect.NEW_WORLDS, edit(changes, this.directory.resolve("defaultconfigs/testmod-server.toml"),
                PackCatalog.Restart.NONE, "1", "2"));
        assertNull(changes.pending(config(), "speed"));
    }

    @Test
    void aRunningGameReloadsFilesAndWaitsForARestartWhereTheSettingSaysSo() {
        ConfigChanges changes = new ConfigChanges(this.location, ChangeRecord.inMemory());
        this.location.connected(message -> true);

        assertEquals(Effect.NOW, edit(changes, config(), PackCatalog.Restart.NONE, "1", "2"));
        assertNull(changes.pending(config(), "speed"));

        assertEquals(Effect.RESTART, edit(changes, config(), PackCatalog.Restart.GAME, "1", "2"));
        assertEquals(Effect.RESTART, edit(changes, config(), PackCatalog.Restart.GAME, "2", "3"));
        assertEquals(Effect.RESTART, changes.pending(config(), "speed"));
        // Back to the value the game still uses: nothing waits.
        assertEquals(Effect.NOW, edit(changes, config(), PackCatalog.Restart.GAME, "3", "1"));
        assertNull(changes.pending(config(), "speed"));

        edit(changes, config(), PackCatalog.Restart.GAME, "1", "2");
        this.location.disconnected();
        assertNull(changes.pending(config(), "speed"));
    }

    @Test
    void editsWaitingForARestartOutlastAReconnectToTheSameGame() throws Exception {
        ConfigChanges changes = new ConfigChanges(this.location, ChangeRecord.inMemory());
        try (GameLock ignored = GameLock.hold(InstancePaths.forGame(this.directory).gameLock())) {
            this.location.connected(message -> true);
            this.location.process(42);
            edit(changes, config(), PackCatalog.Restart.GAME, "1", "2");

            this.location.disconnected();
            changes.refresh();
            assertEquals(Effect.RESTART, changes.pending(config(), "speed"),
                    "the game still runs with the old value");
            this.location.connected(message -> true);
            this.location.process(42);
            assertEquals(Effect.RESTART, changes.pending(config(), "speed"));

            this.location.process(43);
            assertNull(changes.pending(config(), "speed"), "a restarted game read the file when it started");
        }
    }

    @Test
    void aDisabledConfigWatcherMeansEveryEditWaitsForARestart() throws Exception {
        Files.createDirectories(this.directory.resolve("config"));
        Files.writeString(this.directory.resolve("config/fml.toml"), "disableConfigWatcher = true\n");
        ConfigChanges changes = new ConfigChanges(this.location, ChangeRecord.inMemory());
        this.location.connected(message -> true);

        assertEquals(Effect.RESTART, edit(changes, config(), PackCatalog.Restart.NONE, "1", "2"));
    }

    @Test
    void aSettingThatNeedsARejoinWaitsUntilTheOpenWorldCloses() throws Exception {
        Path world = world("World");
        Path file = world.resolve("serverconfig/testmod-server.toml");
        ConfigChanges changes = new ConfigChanges(this.location, ChangeRecord.inMemory());
        this.location.connected(message -> true);
        this.location.playing(new PlayingPayload.Menu());
        assertEquals(ConfigChanges.Location.WORLD, changes.location(file));
        assertEquals(Effect.WORLD_OPENS, edit(changes, file, PackCatalog.Restart.WORLD, "1", "2"));

        this.location.playing(new PlayingPayload.Singleplayer(world.toString()));
        assertEquals(Effect.NOW, edit(changes, file, PackCatalog.Restart.NONE, "1", "2"));
        assertEquals(Effect.REJOIN, edit(changes, file, PackCatalog.Restart.WORLD, "2", "3"));
        changes.refresh();
        assertEquals(Effect.REJOIN, changes.pending(file, "speed"));

        this.location.playing(new PlayingPayload.Menu());
        changes.refresh();
        assertNull(changes.pending(file, "speed"), "the world was left, and reads the file when it opens again");
    }

    @Test
    void aWorldOpenInTheUnconnectedGameIsTheOneItHasOpen() throws Exception {
        Path world = world("World");
        Path file = world.resolve("serverconfig/testmod-server.toml");
        ConfigChanges changes = new ConfigChanges(this.location, ChangeRecord.inMemory());
        try (GameLock ignored = GameLock.hold(InstancePaths.forGame(this.directory).gameLock());
             FileChannel channel = FileChannel.open(world.resolve("session.lock"), StandardOpenOption.WRITE);
             FileLock held = channel.lock()) {
            assertEquals(Effect.REJOIN, edit(changes, file, PackCatalog.Restart.WORLD, "1", "2"));
        }
    }

    private Path config() {
        return this.directory.resolve("config/testmod-common.toml");
    }

    private Path world(String name) {
        try {
            Path world = Files.createDirectories(this.directory.resolve("saves").resolve(name));
            Files.createDirectories(world.resolve("serverconfig"));
            Files.writeString(world.resolve("session.lock"), "☃");
            return world;
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Effect edit(ConfigChanges changes, Path file, PackCatalog.Restart restart, String before,
                                             String after) {
        PackCatalog.ConfigType type = file.toString().contains("server") ? PackCatalog.ConfigType.SERVER : PackCatalog.ConfigType.COMMON;
        return changes.edited(new ChangeRecord.Setting("testmod", file.getFileName().toString(), file, "speed"), type,
                restart, before, after);
    }
}
