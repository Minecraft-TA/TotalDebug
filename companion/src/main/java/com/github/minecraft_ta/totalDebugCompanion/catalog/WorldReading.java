package com.github.minecraft_ta.totalDebugCompanion.catalog;

import com.github.minecraft_ta.totalDebugCompanion.game.GameLocation;
import com.github.minecraft_ta.totalDebugCompanion.game.GameState;
import com.github.minecraft_ta.totalDebugCompanion.pack.GamePacks;
import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;
import com.github.minecraft_ta.totalDebugCompanion.util.FileReading;
import com.github.minecraft_ta.totalDebugCompanion.util.Signal;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
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
    /**
     * The current world: its folder, what its level.dat holds and when its icon was written, so a new icon is a change too;
     * without what it holds where it could not be read, and all null where no world was played yet.
     */
    public record World(Path directory, CurrentWorld.Saved saved, FileTime icon) {
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

    /**
     * The current world as read last; read now where it was not read yet, which fails where it cannot be read. A read
     * that fails later, as while the game writes level.dat, keeps the world read before. Blocking then.
     */
    public World value() throws IOException {
        return this.reading.value();
    }

    /**
     * Reads the current world. A world read before that fails to read now, as while the game writes its level.dat, fails,
     * so the world read before stays; another world that fails to read is published without what it holds, so nothing
     * shows or changes the world before as if it were current.
     */
    private World read(GameState game) throws IOException {
        Path directory = CurrentWorld.directory(game).orElse(null);
        if (directory == null) return new World(null, null, null);
        try {
            return new World(directory, CurrentWorld.read(game, directory), iconWritten(directory));
        } catch (IOException unreadable) {
            if (this.reading.published().map(World::directory).filter(directory::equals).isPresent()) throw unreadable;
            return new World(directory, null, null);
        }
    }

    /** When the world's icon was written, or null without one. */
    private static FileTime iconWritten(Path world) {
        try {
            return Files.getLastModifiedTime(world.resolve("icon.png"));
        } catch (IOException none) {
            return null;
        }
    }

    @Override
    public void close() {
        this.stopFollowing.forEach(Runnable::run);
        this.reading.close();
    }
}
