package com.github.minecraft_ta.totalDebugCompanion.catalog;

import com.github.minecraft_ta.totalDebugCompanion.game.GameLocation;
import com.github.minecraft_ta.totalDebugCompanion.util.FileReading;
import com.github.minecraft_ta.totalDebugCompanion.util.Signal;
import com.github.minecraft_ta.totalDebugCompanion.util.Strand;
import com.github.minecraft_ta.totalDebugCompanion.util.Workers;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * The instance's current world: the one the game has open, otherwise the one played last, read from its folder
 * (docs/SYSTEMS.md, section 2). The World page and the Project tree show what it read; neither reads the world itself.
 * It moves to another world when the game plays one, and reads its world again when the game saves it, opens or leaves
 * it, or its datapacks folder changes. Which world is current is decided on its strand.
 */
public final class WorldReading implements AutoCloseable {
    /** The entries of a world's folder that make up what is read of it. */
    private static final Set<String> ENTRIES = Set.of("level.dat", "level.dat_old", "session.lock", "datapacks");

    /** The current world: its folder and what its level.dat holds, or why there is nothing to show. */
    public record World(Path directory, CurrentWorld.Saved saved, String problem) {
    }

    /** The world followed and its reading, published together. */
    private record Followed(Path directory, FileReading<CurrentWorld.Saved> reading) {
    }

    private final GameLocation location;
    private final Signal changed = new Signal();
    private final Strand strand = Workers.strand();
    private final Runnable stopFollowingGame;
    private volatile Followed followed = new Followed(null, null);
    // Changed on the strand only.
    private Runnable stopReading = () -> { };
    private boolean closed;

    /** Follows the current world of the game {@code location} tells of. Reads which world that is now. Blocking. */
    public WorldReading(GameLocation location) {
        this.location = Objects.requireNonNull(location, "location");
        follow();
        this.stopFollowingGame = location.addListener(change -> this.strand.execute(this::follow));
    }

    /** Fires after the current world changed or what it holds did. */
    public Signal changed() {
        return this.changed;
    }

    /** The current world as read last; read now where it was not read yet. Blocking then. */
    public World value() {
        Followed now = this.followed;
        if (now.directory() == null) return new World(null, null, "No world has been played in this instance yet.");
        try {
            return new World(now.directory(), now.reading().value(), "");
        } catch (IOException unreadable) {
            return new World(now.directory(), null,
                    "The world " + now.directory().getFileName() + " could not be read: " + unreadable.getMessage());
        }
    }

    /** Finds which world is current now and follows it; the one followed so far is read again, as it may have opened. */
    private void follow() {
        if (this.closed) return;
        Path current = CurrentWorld.directory(this.location.read()).orElse(null);
        Followed before = this.followed;
        if (Objects.equals(current, before.directory())) {
            if (before.reading() != null) before.reading().readNow();
            return;
        }
        this.stopReading.run();
        if (current == null) {
            this.stopReading = () -> { };
            this.followed = new Followed(null, null);
        } else {
            FileReading<CurrentWorld.Saved> reading = new FileReading<>(current, entry -> ENTRIES.contains(entry.toString()),
                    world -> CurrentWorld.read(this.location.read(), world), Duration.ofMillis(500));
            Runnable stopTelling = reading.changed().subscribe(this.changed::fire);
            this.stopReading = () -> {
                stopTelling.run();
                reading.close();
            };
            this.followed = new Followed(current, reading);
        }
        if (before.directory() != null || current != null) this.changed.fire();
    }

    @Override
    public void close() {
        this.stopFollowingGame.run();
        this.strand.execute(() -> {
            this.closed = true;
            this.stopReading.run();
        });
    }
}
