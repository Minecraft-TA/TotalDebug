package com.github.minecraft_ta.totalDebugCompanion.catalog;

import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;
import com.github.minecraft_ta.totaldebug.storage.GameLock;
import com.github.minecraft_ta.totaldebug.storage.InstancePaths;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * When configuration edits take effect in the game, and the edits the running game has not applied yet. NeoForge
 * reloads a changed file of a running game unless its config watcher is off; a setting that needs the world rejoined
 * waits until the world it was edited in closes, and one that needs a restart until the game disconnects. Every edit
 * is also entered in the instance's {@link ChangeRecord}, which keeps the value a setting had before Companion first
 * changed it.
 */
public final class ConfigChanges {

    /** When an edit takes effect. */
    public enum Effect {
        NOW("the game reloaded it"),
        REJOIN("takes effect after rejoining the world"),
        RESTART("takes effect after restarting the game"),
        GAME_STARTS("applies when the game starts"),
        WORLD_OPENS("applies when the world opens"),
        NEW_WORLDS("applies to worlds created from now on");

        private final String description;

        Effect(String description) {
            this.description = description;
        }

        public String description() {
            return this.description;
        }

        /** Whether the running game keeps using the previous value until something happens. */
        public boolean pending() {
            return this == REJOIN || this == RESTART;
        }
    }

    /** Where an edited file sits in the game directory. */
    public enum Location { CONFIG, WORLD, DEFAULTS }

    /** An edit the running game has not applied; {@code applied} is the value it still uses. */
    private record Pending(Effect effect, Path world, String applied) {
    }

    private record Key(Path file, String setting) {
    }

    private final Path workspace;
    private final ChangeRecord record;
    private final Map<Key, Pending> pending = new ConcurrentHashMap<>();
    /** One write at a time for the project, so writes to the same file never interleave. */
    private final ExecutorService writes = Executors.newSingleThreadExecutor(task ->
            Thread.ofPlatform().daemon().name("Configuration writes").unstarted(task));
    private volatile boolean connected;
    /** The game process the pending edits wait in, or 0 while it is unknown. */
    private long gameProcess;

    /** {@code workspace} is the game directory, and {@code record} keeps every edit. */
    public ConfigChanges(Path workspace, ChangeRecord record) {
        this.workspace = workspace;
        this.record = Objects.requireNonNull(record, "record");
    }

    public ChangeRecord record() {
        return this.record;
    }

    /** Runs {@code write} after the project's earlier writes; refused once the project closes. */
    public <T> CompletableFuture<T> write(Supplier<T> write) {
        try {
            return CompletableFuture.supplyAsync(write, this.writes);
        } catch (RejectedExecutionException closed) {
            return CompletableFuture.failedFuture(new IOException("The project is closing; the change was not written"));
        }
    }

    /**
     * The project's writes to the game's files, one at a time, which the project finishes before its change record
     * closes. Refuses work once the project closes.
     */
    public Executor writes() {
        return this.writes;
    }

    /**
     * Stops taking writes and waits until those already taken have finished, so every file written is also recorded
     * before the change record closes. An interruption does not cut the wait short; it is kept for the caller.
     */
    public void close() {
        this.writes.shutdown();
        boolean interrupted = false;
        while (true) {
            try {
                if (this.writes.awaitTermination(1, TimeUnit.MINUTES)) break;
            } catch (InterruptedException interruption) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    public void gameConnected() {
        this.connected = true;
    }

    /**
     * The connected game's process. Another process than the one the pending edits wait in started after it, and read
     * every file when it started.
     */
    public synchronized void gameProcess(long processId) {
        // The first game seen may be the one the edits wait in; only a known, different process has restarted.
        if (this.gameProcess != 0 && processId != this.gameProcess) this.pending.clear();
        this.gameProcess = processId;
    }

    /**
     * The game lost its connection. Pending edits stay while it still runs, since a reconnect does not apply them; a
     * game that closed reads every file again when it starts.
     */
    public void gameDisconnected() {
        this.connected = false;
        if (!gameLockHeld()) this.pending.clear();
    }

    /** Whether a game runs in the instance, connected or not. Blocking; it looks at the game's lock. */
    private boolean gameRunning() {
        return this.connected || gameLockHeld();
    }

    private boolean gameLockHeld() {
        return this.workspace != null && GameLock.held(InstancePaths.forGame(this.workspace).gameLock());
    }

    /** Where a configuration file is: a world's server configuration, the defaults for new worlds, or the config folder. */
    public Location location(Path file) {
        if (this.workspace == null) return Location.CONFIG;
        Path normalized = file.toAbsolutePath().normalize();
        if (normalized.startsWith(this.workspace.resolve("defaultconfigs").toAbsolutePath().normalize())) return Location.DEFAULTS;
        return world(normalized) == null ? Location.CONFIG : Location.WORLD;
    }

    /**
     * Records that {@code target} changed from {@code previous} to {@code written} and tells when the game uses the new
     * value. Blocking; it looks at the game directory.
     */
    public Effect edited(ChangeRecord.Setting target, PackCatalog.ConfigType type, PackCatalog.Restart restart,
                         String previous, String written) {
        this.record.changed(target, previous, written);
        Key key = new Key(target.file(), target.setting());
        Pending earlier = this.pending.get(key);
        Location location = location(target.file());
        Path world = location == Location.WORLD ? world(key.file()) : null;
        Effect effect = effect(type, restart, location, world);
        if (!effect.pending()) {
            this.pending.remove(key);
        } else if (earlier != null && earlier.applied().equals(written)) {
            // Set back to the value the game still uses: nothing is waiting any more.
            this.pending.remove(key);
            return Effect.NOW;
        } else {
            Path openWorld = effect == Effect.REJOIN ? (world != null ? world : openWorld()) : null;
            this.pending.put(key, new Pending(effect, openWorld, earlier == null ? previous : earlier.applied()));
        }
        return effect;
    }

    private Effect effect(PackCatalog.ConfigType type, PackCatalog.Restart restart, Location location, Path world) {
        if (location == Location.DEFAULTS) return Effect.NEW_WORLDS;
        if (!gameRunning()) return location == Location.WORLD ? Effect.WORLD_OPENS : Effect.GAME_STARTS;
        if (type == PackCatalog.ConfigType.STARTUP || restart == PackCatalog.Restart.GAME || !watchesFiles()) {
            return Effect.RESTART;
        }
        if (location == Location.WORLD && !open(world)) return Effect.WORLD_OPENS;
        if (restart == PackCatalog.Restart.WORLD) return openWorld() == null ? Effect.NOW : Effect.REJOIN;
        return Effect.NOW;
    }

    /** What the running game still waits for before it uses the edited value of a setting, or null. */
    public Effect pending(Path file, String setting) {
        Pending entry = this.pending.get(new Key(file.toAbsolutePath().normalize(), setting));
        return entry == null ? null : entry.effect();
    }

    /** The value a setting had before Companion first changed it, or null when Companion did not change it. */
    public String original(Path file, String setting) {
        return this.record.original(file, setting);
    }

    /**
     * Drops rejoin edits whose world has closed since, and every pending edit once the game has closed. Blocking; it
     * looks at the game's and the worlds' locks.
     */
    public void refresh() {
        if (!gameRunning()) {
            this.pending.clear();
            return;
        }
        List<Key> applied = new ArrayList<>();
        this.pending.forEach((key, entry) -> {
            if (entry.effect() == Effect.REJOIN && (entry.world() == null || !open(entry.world()))) applied.add(key);
        });
        applied.forEach(this.pending::remove);
    }

    /** The world directory holding {@code file} as {@code saves/<world>/serverconfig/<file>}, or null. */
    private Path world(Path file) {
        if (this.workspace == null) return null;
        Path saves = this.workspace.resolve("saves").toAbsolutePath().normalize();
        for (Path world = file.getParent(); world != null; world = world.getParent()) {
            if (saves.equals(world.getParent())) return file.startsWith(world.resolve("serverconfig")) ? world : null;
        }
        return null;
    }

    /** The world the game has open, found by its held session lock, or null. */
    private Path openWorld() {
        if (this.workspace == null) return null;
        try (DirectoryStream<Path> saves = Files.newDirectoryStream(this.workspace.resolve("saves"), Files::isDirectory)) {
            for (Path world : saves) {
                if (open(world)) return world;
            }
        } catch (IOException noSaves) {
            // Without saves no world is open.
        }
        return null;
    }

    /**
     * Whether a game holds the world's {@code session.lock}. Windows refuses to read a locked file, so looking never
     * takes the lock there; elsewhere the lock is taken and released at once.
     */
    static boolean open(Path world) {
        if (world == null) return false;
        Path lock = world.resolve("session.lock");
        if (!Files.isRegularFile(lock)) return false;
        try {
            Files.readAllBytes(lock);
        } catch (IOException locked) {
            return true;
        }
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) return false;
        try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.WRITE)) {
            FileLock held = channel.tryLock();
            if (held == null) return true;
            held.release();
            return false;
        } catch (OverlappingFileLockException ownLock) {
            return true;
        } catch (IOException unreadable) {
            return false;
        }
    }

    /** Whether NeoForge reloads changed configuration files; {@code config/fml.toml} can turn that off. */
    private boolean watchesFiles() {
        if (this.workspace == null) return true;
        Path fml = this.workspace.resolve("config").resolve("fml.toml");
        if (!Files.isRegularFile(fml)) return true;
        try {
            return !Objects.equals(ConfigValues.read(fml).values().get("disableConfigWatcher"), "true");
        } catch (IOException unreadable) {
            return true;
        }
    }
}
