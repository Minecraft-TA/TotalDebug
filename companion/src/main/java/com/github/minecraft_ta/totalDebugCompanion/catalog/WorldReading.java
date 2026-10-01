package com.github.minecraft_ta.totalDebugCompanion.catalog;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.nio.file.attribute.FileTime;
import com.github.minecraft_ta.totalDebugCompanion.util.FileWatch;
import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;
import com.github.minecraft_ta.totalDebugCompanion.pack.GamePacks;
import com.github.minecraft_ta.totalDebugCompanion.game.GameState;
import com.github.minecraft_ta.totalDebugCompanion.game.GameLocation;
import com.github.minecraft_ta.totalDebugCompanion.util.FileReading;
import com.github.minecraft_ta.totalDebugCompanion.util.Signal;
import com.github.minecraft_ta.totalDebugCompanion.util.Strand;
import com.github.minecraft_ta.totalDebugCompanion.util.Workers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * The instance's current world: the one the game has open, otherwise the one played last, read from its folder
 * (docs/SYSTEMS.md, section 2). The World page and the Project tree show what it read; neither reads the world itself.
 * Which world is current is decided on its strand, again when the game connects, plays another world or leaves one, and
 * when a world of {@code saves} changes, as when a game Companion is not connected to opens or saves one. The current
 * world is read again then, and after Companion changed its datapacks. Its own folder is watched only while the connected
 * game plays it, which the game tells only once it let go of the world: on Windows a watched folder cannot be deleted or
 * renamed, as by the game's Delete World.
 */
public final class WorldReading implements AutoCloseable {
    /** The entries of a world's folder that make up what is read of it. */
    private static final long SETTLE_MILLIS = 500;
    private static final Set<String> ENTRIES = Set.of("level.dat", "level.dat_old", "session.lock", "datapacks", "icon.png");

    /** The current world: its folder and what its level.dat holds, or why there is nothing to show. */
    public record World(Path directory, CurrentWorld.Saved saved, String problem) {
    }

    /** What is read of a world's folder: its level.dat, and when its icon was written, so a new icon is a change too. */
    private record Read(CurrentWorld.Saved saved, FileTime icon) {
    }

    /** The world followed and its reading, published together. */
    private record Followed(Path directory, FileReading<Read> reading) {
    }

    private final GameLocation location;
    private final Signal changed = new Signal();
    private final Runnable stopFollowingSaves;
    private final Runnable stopFollowingDatapacks;
    private final Strand strand = Workers.strand();
    private final Runnable stopFollowingGame;
    private volatile Followed followed = new Followed(null, null);
    // Changed on the strand only.
    private Runnable stopReading = () -> { };
    /** The played world whose datapacks folder is watched, or null: a change inside it is not told by the world's own watch. */
    private Path datapacksOf;
    private Runnable stopDatapacks = () -> { };
    private boolean closed;

    /**
     * Follows the current world of the game {@code location} tells of, whose datapacks {@code packs} names. Reads which
     * world that is now. Blocking.
     */
    public WorldReading(GameLocation location, GamePacks packs) {
        this.location = Objects.requireNonNull(location, "location");
        // A game opening a world creates its session lock before it takes it: which world is open is looked at after that.
        this.stopFollowingSaves = FileWatch.shared().watch(location.workspace().resolve("saves"), world -> true,
                () -> Workers.later(SETTLE_MILLIS, this.strand, this::follow));
        // Companion changed the datapacks, also in the level.dat of a world the game does not hold.
        this.stopFollowingDatapacks = packs.changed(ChangeRecord.PackSide.DATA).subscribe(() -> this.strand.execute(this::follow));
        Runnable stopConnection = location.connectionChanged().subscribe(() -> this.strand.execute(this::follow));
        Runnable stopPlaying = location.playingChanged().subscribe(() -> this.strand.execute(this::follow));
        this.stopFollowingGame = () -> {
            stopConnection.run();
            stopPlaying.run();
        };
        // Followed after the triggers are, so none is missed in between; on the strand, as every later look.
        CompletableFuture.runAsync(this::follow, this.strand).join();
    }

    /** Fires after the current world changed or what it holds did. */
    public Signal changed() {
        return this.changed;
    }

    /** The name of the current world as read last, without reading; empty where none was read yet. */
    public Optional<String> publishedName() {
        Followed now = this.followed;
        if (now.reading() == null) return Optional.empty();
        return now.reading().published().map(read -> read.saved().name());
    }

    /** The current world as read last; read now where it was not read yet. Blocking then. */
    public World value() {
        Followed now = this.followed;
        if (now.directory() == null) return new World(null, null, "No world has been played in this instance yet.");
        try {
            return new World(now.directory(), now.reading().value().saved(), "");
        } catch (IOException unreadable) {
            return new World(now.directory(), null,
                    "The world " + now.directory().getFileName() + " could not be read: " + unreadable.getMessage());
        }
    }

    /** Finds which world is current now and follows it; the one followed so far is read again, as it may have opened. */
    private void follow() {
        if (this.closed) return;
        GameState game = this.location.read();
        Path current = CurrentWorld.directory(game).orElse(null);
        Followed before = this.followed;
        boolean played = current != null && game.plays(current);
        if (Objects.equals(current, before.directory())) {
            if (before.reading() != null) {
                before.reading().watch(played);
                watchDatapacks(played ? current : null, before.reading());
                before.reading().readNow();
            }
            return;
        }
        this.stopReading.run();
        if (current == null) {
            watchDatapacks(null, null);
            this.stopReading = () -> { };
            this.followed = new Followed(null, null);
        } else {
            FileReading<Read> reading = new FileReading<>(current, entry -> ENTRIES.contains(entry.toString()),
                    world -> new Read(CurrentWorld.read(this.location.read(), world), iconWritten(world)), Duration.ofMillis(SETTLE_MILLIS));
            reading.watch(played);
            watchDatapacks(played ? current : null, reading);
            Runnable stopTelling = reading.changed().subscribe(this.changed::fire);
            this.stopReading = () -> {
                stopTelling.run();
                reading.close();
            };
            this.followed = new Followed(current, reading);
            // Told once the new world's first read landed, so its followers find it published, as the tab its name.
            Workers.files().execute(() -> {
                try {
                    reading.value();
                } catch (IOException unreadable) {
                    // The World page shows why.
                }
                this.changed.fire();
            });
            return;
        }
        if (before.directory() != null) this.changed.fire();
    }

    /** Watches the datapacks folder of {@code world}, which the game plays, or none; a change there reads it again. */
    private void watchDatapacks(Path world, FileReading<Read> reading) {
        if (Objects.equals(world, this.datapacksOf)) return;
        this.stopDatapacks.run();
        this.stopDatapacks = () -> { };
        this.datapacksOf = world;
        if (world != null) this.stopDatapacks = FileWatch.shared().watch(world.resolve("datapacks"), entry -> true, reading::readNow);
    }

    /** When the world's icon was written, or null without one. */
    private static FileTime iconWritten(Path world) {
        try {
            Path icon = world.resolve("icon.png");
            return Files.isRegularFile(icon) ? Files.getLastModifiedTime(icon) : null;
        } catch (IOException unreadable) {
            return null;
        }
    }

    @Override
    public void close() {
        this.stopFollowingGame.run();
        this.stopFollowingSaves.run();
        this.stopFollowingDatapacks.run();
        this.strand.execute(() -> {
            this.closed = true;
            this.stopDatapacks.run();
            this.stopReading.run();
        });
    }
}
