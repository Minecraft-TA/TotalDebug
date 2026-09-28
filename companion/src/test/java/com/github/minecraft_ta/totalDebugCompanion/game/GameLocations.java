package com.github.minecraft_ta.totalDebugCompanion.game;

import java.nio.file.Path;

/** Game locations for tests: the instance's worlds are read from its files, and whether a game runs is given. */
public final class GameLocations {
    private GameLocations() {
    }

    /** The game of {@code workspace}, running or closed as {@code running} says; its worlds' locks are the real ones. */
    public static GameLocation of(Path workspace, boolean running) {
        GameLocation.Files files = GameLocation.Files.of(workspace);
        return new GameLocation(workspace, new GameLocation.Files() {
            @Override public boolean gameRunning() { return running; }
            @Override public boolean held(Path world) { return files.held(world); }
            @Override public Path heldWorld() { return files.heldWorld(); }
            @Override public Path lastPlayed() { return files.lastPlayed(); }
        });
    }
}
