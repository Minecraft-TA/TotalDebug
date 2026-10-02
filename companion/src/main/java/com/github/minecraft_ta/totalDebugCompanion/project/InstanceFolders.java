package com.github.minecraft_ta.totalDebugCompanion.project;

import com.github.minecraft_ta.totalDebugCompanion.catalog.GameLogs;
import com.github.minecraft_ta.totalDebugCompanion.game.GameLocation;
import com.github.minecraft_ta.totalDebugCompanion.util.FileReading;
import com.github.minecraft_ta.totalDebugCompanion.util.Signal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The three facts about the instance's folders that the Project tree's roots need from the disk: whether the scripts
 * folder exists, whether {@code saves} exists, and whether the game wrote logs or crash reports (docs/SYSTEMS.md,
 * section 2). Read on the reading's strand when the project opens, when the user comes back, and when the game connects
 * or plays another world, since the game makes {@code saves} and writes its logs; a caller that made the scripts folder
 * asks for a read itself ({@link #refresh}).
 */
public final class InstanceFolders implements AutoCloseable {
    /** What the folders hold; nothing, before the first read. */
    public record Folders(boolean scripts, boolean saves, boolean logs) {
        public static final Folders NONE = new Folders(false, false, false);
    }

    private final FileReading<Folders> reading;
    private final List<Runnable> stopFollowing;

    /** Follows the folders of {@code workspace}, the game directory, with its scripts folder at {@code scripts}. */
    public InstanceFolders(Path scripts, Path workspace, GameLocation location) {
        this.reading = new FileReading<>(() -> new Folders(Files.isDirectory(scripts),
                Files.isDirectory(workspace.resolve("saves")), GameLogs.any(workspace)));
        this.stopFollowing = List.of(location.connectionChanged().subscribe(this.reading::refresh),
                location.playingChanged().subscribe(this.reading::refresh));
        this.reading.refresh();
    }

    /** Fires after a read found the folders other than before. */
    public Signal changed() {
        return this.reading.changed();
    }

    /** The folders as read last, without reading; {@link Folders#NONE} before the first read. */
    public Folders published() {
        return this.reading.published().orElse(Folders.NONE);
    }

    /** Reads the folders again, as after Companion made the scripts folder; completes with what that read found. */
    public CompletableFuture<Folders> refresh() {
        return this.reading.refresh();
    }

    @Override
    public void close() {
        this.stopFollowing.forEach(Runnable::run);
        this.reading.close();
    }
}
