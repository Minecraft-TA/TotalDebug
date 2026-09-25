package com.github.minecraft_ta.totalDebugCompanion.catalog;

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
import java.util.concurrent.ConcurrentHashMap;

/**
 * When configuration edits take effect in the game, and the edits the running game has not applied yet. NeoForge
 * reloads a changed file of a running game unless its config watcher is off; a setting that needs the world rejoined
 * waits until the world it was edited in closes, and one that needs a restart until the game disconnects. It also
 * keeps the value each setting had before it was first edited while Companion runs, so edits stand apart from values
 * that already differed.
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
    private final Map<Key, Pending> pending = new ConcurrentHashMap<>();
    /** The value each edited setting had before its first edit. */
    private final Map<Key, String> originals = new ConcurrentHashMap<>();
    private volatile boolean gameRunning;

    /** {@code workspace} is the game directory. */
    public ConfigChanges(Path workspace) {
        this.workspace = workspace;
    }

    public void gameConnected() {
        this.gameRunning = true;
    }

    /** The game closed or lost its connection; it reads every file again when it starts. */
    public void gameDisconnected() {
        this.gameRunning = false;
        this.pending.clear();
    }

    public boolean gameRunning() {
        return this.gameRunning;
    }

    /** Where a configuration file is: a world's server configuration, the defaults for new worlds, or the config folder. */
    public Location location(Path file) {
        if (this.workspace == null) return Location.CONFIG;
        Path normalized = file.toAbsolutePath().normalize();
        if (normalized.startsWith(this.workspace.resolve("defaultconfigs").toAbsolutePath().normalize())) return Location.DEFAULTS;
        return world(normalized) == null ? Location.CONFIG : Location.WORLD;
    }

    /**
     * Records that {@code setting} of {@code file} changed from {@code previous} to {@code written} and tells when the
     * game uses the new value. Blocking; it looks at the game directory.
     */
    public Effect edited(Path file, PackCatalog.ConfigType type, String setting, PackCatalog.Restart restart,
                         String previous, String written) {
        Key key = new Key(file.toAbsolutePath().normalize(), setting);
        String original = this.originals.computeIfAbsent(key, ignored -> previous);
        if (original.equals(written)) this.originals.remove(key);
        Pending earlier = this.pending.get(key);
        Location location = location(file);
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
        if (!this.gameRunning) return location == Location.WORLD ? Effect.WORLD_OPENS : Effect.GAME_STARTS;
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

    /** The value a setting had before it was first edited while Companion runs, or null when it was not edited. */
    public String original(Path file, String setting) {
        return this.originals.get(new Key(file.toAbsolutePath().normalize(), setting));
    }

    /** Drops rejoin edits whose world has closed since. Blocking; it looks at the worlds' locks. */
    public void refresh() {
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
