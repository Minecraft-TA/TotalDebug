package com.github.minecraft_ta.totalDebugCompanion.catalog;

import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;
import com.github.minecraft_ta.totaldebug.storage.GameLock;
import com.github.minecraft_ta.totaldebug.storage.InstancePaths;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
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

    @Test
    void closingFinishesTheWritesAlreadyTakenAndRefusesLaterOnes() throws Exception {
        ConfigChanges changes = new ConfigChanges(this.directory, ChangeRecord.inMemory());
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<String> taken = changes.write(() -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return "written";
        });

        CompletableFuture<Void> closing = CompletableFuture.runAsync(changes::close);
        release.countDown();
        closing.get(10, TimeUnit.SECONDS);

        assertEquals("written", taken.getNow(null), "a write taken before closing is finished, and so recorded");
        ExecutionException refused = assertThrows(ExecutionException.class,
                () -> changes.write(() -> "late").get(5, TimeUnit.SECONDS));
        assertEquals("The project is closing; the change was not written", refused.getCause().getMessage());
    }

    @Test
    void aClosedGameUsesEditsWhenItStarts() {
        ConfigChanges changes = new ConfigChanges(this.directory, ChangeRecord.inMemory());

        assertEquals(ConfigChanges.Effect.GAME_STARTS, edit(changes, config(), PackCatalog.Restart.GAME, "1", "2"));
        assertEquals(ConfigChanges.Effect.WORLD_OPENS, edit(changes, world("World").resolve("serverconfig/testmod-server.toml"),
                PackCatalog.Restart.NONE, "1", "2"));
        assertEquals(ConfigChanges.Effect.NEW_WORLDS, edit(changes, this.directory.resolve("defaultconfigs/testmod-server.toml"),
                PackCatalog.Restart.NONE, "1", "2"));
        assertNull(changes.pending(config(), "speed"));
    }

    @Test
    void aRunningGameReloadsFilesAndWaitsForARestartWhereTheSettingSaysSo() {
        ConfigChanges changes = new ConfigChanges(this.directory, ChangeRecord.inMemory());
        changes.gameConnected();

        assertEquals(ConfigChanges.Effect.NOW, edit(changes, config(), PackCatalog.Restart.NONE, "1", "2"));
        assertNull(changes.pending(config(), "speed"));
        assertEquals("1", changes.original(config(), "speed"), "the value before the first edit");
        edit(changes, config(), PackCatalog.Restart.NONE, "2", "1");
        assertNull(changes.original(config(), "speed"), "set back, the setting is no longer edited");

        assertEquals(ConfigChanges.Effect.RESTART, edit(changes, config(), PackCatalog.Restart.GAME, "1", "2"));
        assertEquals(ConfigChanges.Effect.RESTART, edit(changes, config(), PackCatalog.Restart.GAME, "2", "3"));
        assertEquals(ConfigChanges.Effect.RESTART, changes.pending(config(), "speed"));
        // Back to the value the game still uses: nothing waits.
        assertEquals(ConfigChanges.Effect.NOW, edit(changes, config(), PackCatalog.Restart.GAME, "3", "1"));
        assertNull(changes.pending(config(), "speed"));

        edit(changes, config(), PackCatalog.Restart.GAME, "1", "2");
        changes.gameDisconnected();
        assertNull(changes.pending(config(), "speed"));
    }

    @Test
    void editsWaitingForARestartOutlastAReconnectToTheSameGame() throws Exception {
        ConfigChanges changes = new ConfigChanges(this.directory, ChangeRecord.inMemory());
        try (GameLock ignored = GameLock.hold(InstancePaths.forGame(this.directory).gameLock())) {
            changes.gameConnected();
            changes.gameProcess(42);
            edit(changes, config(), PackCatalog.Restart.GAME, "1", "2");

            changes.gameDisconnected();
            changes.refresh();
            assertEquals(ConfigChanges.Effect.RESTART, changes.pending(config(), "speed"),
                    "the game still runs with the old value");
            changes.gameConnected();
            changes.gameProcess(42);
            assertEquals(ConfigChanges.Effect.RESTART, changes.pending(config(), "speed"));

            changes.gameProcess(43);
            assertNull(changes.pending(config(), "speed"), "a restarted game read the file when it started");
        }
    }

    @Test
    void aDisabledConfigWatcherMeansEveryEditWaitsForARestart() throws Exception {
        Files.createDirectories(this.directory.resolve("config"));
        Files.writeString(this.directory.resolve("config/fml.toml"), "disableConfigWatcher = true\n");
        ConfigChanges changes = new ConfigChanges(this.directory, ChangeRecord.inMemory());
        changes.gameConnected();

        assertEquals(ConfigChanges.Effect.RESTART, edit(changes, config(), PackCatalog.Restart.NONE, "1", "2"));
    }

    @Test
    void aSettingThatNeedsARejoinWaitsUntilTheOpenWorldCloses() throws Exception {
        Path world = world("World");
        Path file = world.resolve("serverconfig/testmod-server.toml");
        ConfigChanges changes = new ConfigChanges(this.directory, ChangeRecord.inMemory());
        changes.gameConnected();
        assertEquals(ConfigChanges.Location.WORLD, changes.location(file));
        assertEquals(ConfigChanges.Effect.WORLD_OPENS, edit(changes, file, PackCatalog.Restart.WORLD, "1", "2"));

        try (FileChannel channel = FileChannel.open(world.resolve("session.lock"), StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            assertTrue(ConfigChanges.open(world));
            assertEquals(ConfigChanges.Effect.NOW, edit(changes, file, PackCatalog.Restart.NONE, "1", "2"));
            assertEquals(ConfigChanges.Effect.REJOIN, edit(changes, file, PackCatalog.Restart.WORLD, "2", "3"));
            changes.refresh();
            assertEquals(ConfigChanges.Effect.REJOIN, changes.pending(file, "speed"));
        }
        assertFalse(ConfigChanges.open(world));
        changes.refresh();
        assertNull(changes.pending(file, "speed"));
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

    private static ConfigChanges.Effect edit(ConfigChanges changes, Path file, PackCatalog.Restart restart, String before,
                                             String after) {
        PackCatalog.ConfigType type = file.toString().contains("server") ? PackCatalog.ConfigType.SERVER : PackCatalog.ConfigType.COMMON;
        return changes.edited(new ChangeRecord.Setting("testmod", file.getFileName().toString(), file, "speed"), type,
                restart, before, after);
    }
}
