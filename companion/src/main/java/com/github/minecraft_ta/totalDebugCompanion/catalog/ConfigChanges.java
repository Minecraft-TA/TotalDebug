package com.github.minecraft_ta.totalDebugCompanion.catalog;

import com.github.minecraft_ta.totalDebugCompanion.change.Effect;
import com.github.minecraft_ta.totalDebugCompanion.game.GameLocation;
import com.github.minecraft_ta.totalDebugCompanion.game.GameState;
import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
 * waits until the world it was edited in closes, and one that needs a restart until the game disconnects. The change
 * pipeline writes and records the edits ({@link ConfigSettings}); the instance's {@link ChangeRecord} keeps the value a
 * setting had before Companion first changed it.
 */
public final class ConfigChanges {

    /** Where an edited file sits in the game directory. */
    public enum Location { CONFIG, WORLD, DEFAULTS }

    /** An edit the running game has not applied; {@code applied} is the value it still uses. */
    private record Pending(Effect effect, Path world, String applied) {
    }

    private record Key(Path file, String setting) {
    }

    private final GameLocation location;
    private final Path workspace;
    private final ChangeRecord record;
    private final Map<Key, Pending> pending = new ConcurrentHashMap<>();
    /** One write at a time for the project, so writes to the same file never interleave. */
    private final ExecutorService writes = Executors.newSingleThreadExecutor(task ->
            Thread.ofPlatform().daemon().name("Configuration writes").unstarted(task));
    /** The game process the pending edits wait in, or 0 while it is unknown. */
    private long gameProcess;

    /** {@code location} tells where the game of the instance is, and {@code record} keeps every edit. */
    public ConfigChanges(GameLocation location, ChangeRecord record) {
        this.location = Objects.requireNonNull(location, "location");
        this.workspace = location.workspace();
        this.record = Objects.requireNonNull(record, "record");
        location.addListener(change -> {
            switch (change) {
                case PROCESS -> gameProcess(location.process());
                case DISCONNECTED -> gameDisconnected();
                default -> { }
            }
        });
    }

    /** Where the game of the instance is. */
    public GameLocation location() {
        return this.location;
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

    /**
     * The connected game's process. Another process than the one the pending edits wait in started after it, and read
     * every file when it started.
     */
    private synchronized void gameProcess(long processId) {
        // The first game seen may be the one the edits wait in; only a known, different process has restarted.
        if (this.gameProcess != 0 && processId != this.gameProcess) this.pending.clear();
        this.gameProcess = processId;
    }

    /**
     * The game lost its connection. Pending edits stay while it still runs, since a reconnect does not apply them; a
     * game that closed reads every file again when it starts.
     */
    private void gameDisconnected() {
        if (!this.location.read().running()) this.pending.clear();
    }

    /** Where a configuration file is: a world's server configuration, the defaults for new worlds, or the config folder. */
    public Location location(Path file) {
        Path normalized = file.toAbsolutePath().normalize();
        if (normalized.startsWith(this.workspace.resolve("defaultconfigs").toAbsolutePath().normalize())) return Location.DEFAULTS;
        return world(normalized) == null ? Location.CONFIG : Location.WORLD;
    }

    /**
     * Tells when the game uses the value {@code target} was changed to from {@code previous}, and keeps it pending while
     * the running game waits for a rejoin or a restart. Blocking; it looks at the game directory.
     */
    public Effect edited(ChangeRecord.Setting target, PackCatalog.ConfigType type, PackCatalog.Restart restart,
                         String previous, String written) {
        Key key = new Key(target.file(), target.setting());
        Pending earlier = this.pending.get(key);
        Location location = location(target.file());
        Path world = location == Location.WORLD ? world(key.file()) : null;
        GameState state = this.location.read();
        Effect effect = effect(state, type, restart, location, world);
        if (!effect.pending()) {
            this.pending.remove(key);
        } else if (earlier != null && earlier.applied().equals(written)) {
            // Set back to the value the game still uses: nothing is waiting any more.
            this.pending.remove(key);
            return Effect.NOW;
        } else {
            Path openWorld = effect == Effect.REJOIN ? (world != null ? world : state.openWorld()) : null;
            this.pending.put(key, new Pending(effect, openWorld, earlier == null ? previous : earlier.applied()));
        }
        return effect;
    }

    private Effect effect(GameState state, PackCatalog.ConfigType type, PackCatalog.Restart restart, Location location, Path world) {
        if (location == Location.DEFAULTS) return Effect.NEW_WORLDS;
        if (!state.running()) return location == Location.WORLD ? Effect.WORLD_OPENS : Effect.GAME_STARTS;
        if (type == PackCatalog.ConfigType.STARTUP || restart == PackCatalog.Restart.GAME || !watchesFiles()) {
            return Effect.RESTART;
        }
        if (location == Location.WORLD && !state.isOpen(world)) return Effect.WORLD_OPENS;
        if (restart == PackCatalog.Restart.WORLD) return state.openWorld() == null ? Effect.NOW : Effect.REJOIN;
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
        GameState state = this.location.read();
        if (!state.running()) {
            this.pending.clear();
            return;
        }
        List<Key> applied = new ArrayList<>();
        this.pending.forEach((key, entry) -> {
            if (entry.effect() == Effect.REJOIN && (entry.world() == null || !state.isOpen(entry.world()))) applied.add(key);
        });
        applied.forEach(this.pending::remove);
    }

    /** The world directory holding {@code file} as {@code saves/<world>/serverconfig/<file>}, or null. */
    private Path world(Path file) {
        Path saves = this.workspace.resolve("saves").toAbsolutePath().normalize();
        for (Path world = file.getParent(); world != null; world = world.getParent()) {
            if (saves.equals(world.getParent())) return file.startsWith(world.resolve("serverconfig")) ? world : null;
        }
        return null;
    }

    /** Whether NeoForge reloads changed configuration files; {@code config/fml.toml} can turn that off. */
    private boolean watchesFiles() {
        Path fml = this.workspace.resolve("config").resolve("fml.toml");
        if (!Files.isRegularFile(fml)) return true;
        try {
            return !Objects.equals(ConfigValues.read(fml).values().get("disableConfigWatcher"), "true");
        } catch (IOException unreadable) {
            return true;
        }
    }
}
