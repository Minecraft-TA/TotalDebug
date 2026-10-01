package com.github.minecraft_ta.totalDebugCompanion.catalog;

import com.github.minecraft_ta.totalDebugCompanion.game.GameLocation;
import com.github.minecraft_ta.totalDebugCompanion.game.GameState;
import com.github.minecraft_ta.totalDebugCompanion.pack.GamePacks;
import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;
import com.github.minecraft_ta.totalDebugCompanion.util.FileReading;
import com.github.minecraft_ta.totalDebugCompanion.util.Signal;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The instance's current world: the one the game has open, otherwise the one played last, read from its folder
 * (docs/SYSTEMS.md, section 2). The World page, the Project tree and the World tab's title show what it read; none reads
 * the world itself. It is read again when the game connects, plays another world or leaves one, after Companion changed
 * the datapacks, and when the user comes back to Companion, as from a game that saved the world meanwhile. It is first
 * read as it is made, so the World tab names the world also while its page was never shown.
 */
public final class WorldReading implements AutoCloseable {
    /** The current world: its folder and what its level.dat holds, or why there is nothing to show. */
    public record World(Path directory, CurrentWorld.Saved saved, String problem) {
    }

    private final FileReading<World> reading;
    private final List<Runnable> stopFollowing;

    /** Follows the current world of the game {@code location} tells of, whose datapacks {@code packs} names. */
    public WorldReading(GameLocation location, GamePacks packs) {
        Objects.requireNonNull(location, "location");
        this.reading = new FileReading<>(() -> read(location.read()));
        this.stopFollowing = List.of(location.connectionChanged().subscribe(this.reading::refresh),
                location.playingChanged().subscribe(this.reading::refresh),
                // Also in the level.dat of a world the game does not hold.
                packs.changed(ChangeRecord.PackSide.DATA).subscribe(this.reading::refresh));
        this.reading.refresh();
    }

    /** Fires after the current world changed or what it holds did. */
    public Signal changed() {
        return this.reading.changed();
    }

    /** The name of the current world as read last, without reading; empty where none was read yet. */
    public Optional<String> publishedName() {
        return this.reading.published().map(World::saved).map(CurrentWorld.Saved::name);
    }

    /** The current world as read last; read now where it was not read yet. Blocking then. */
    public World value() {
        try {
            return this.reading.value();
        } catch (IOException stopped) {
            // The reader tells a world it cannot read as a problem: only a read interrupted gets here.
            return new World(null, null, "The world could not be read: " + stopped.getMessage());
        }
    }

    private static World read(GameState game) {
        Path directory = CurrentWorld.directory(game).orElse(null);
        if (directory == null) return new World(null, null, "No world has been played in this instance yet.");
        try {
            return new World(directory, CurrentWorld.read(game, directory), "");
        } catch (IOException unreadable) {
            return new World(directory, null, "The world " + directory.getFileName() + " could not be read: " + unreadable.getMessage());
        }
    }

    @Override
    public void close() {
        this.stopFollowing.forEach(Runnable::run);
        this.reading.close();
    }
}
