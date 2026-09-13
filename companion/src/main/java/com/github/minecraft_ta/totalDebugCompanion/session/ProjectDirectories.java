package com.github.minecraft_ta.totalDebugCompanion.session;

import java.nio.file.Files;
import java.nio.file.Path;

/** Resolves user-selected instance directories without creating project storage. */
public final class ProjectDirectories {
    private ProjectDirectories() { }

    public static CompanionProfile resolve(Path selected) {
        Path game = PrismInstances.gameDirectory(selected);
        if (!Files.isDirectory(game)
                || !(Files.isDirectory(game.resolve("mods")) || Files.isDirectory(game.resolve("total-debug")))) {
            throw new IllegalArgumentException("Choose a Minecraft instance containing mods or existing total-debug data.");
        }
        return CompanionProfile.forGame(game);
    }
}
