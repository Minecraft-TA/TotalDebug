package com.github.minecraft_ta.totalDebugCompanion.session;

import java.nio.file.Files;
import java.nio.file.Path;

/** Resolves user-selected instance directories without creating project storage. */
public final class ProjectDirectories {
    private ProjectDirectories() { }

    public static CompanionProfile resolve(Path selected) {
        Path location = selected.toAbsolutePath().normalize();
        if (!Files.isDirectory(location)) throw new IllegalArgumentException("Directory not found: " + location);
        String name = location.getFileName() == null ? "" : location.getFileName().toString();
        Path game = PrismInstances.gameDirectory(location);
        if (game.equals(location) && (name.equals("mods") || name.equals("total-debug")) && location.getParent() != null)
            game = location.getParent();
        Path data = game.resolve("total-debug");
        if (!Files.isDirectory(game)
                || !(Files.isDirectory(game.resolve("mods")) || Files.isDirectory(data.resolve("scripts"))
                || Files.isRegularFile(data.resolve("state.json")) || Files.isRegularFile(data.resolve("cache/runtime/inventory.json")))) {
            throw new IllegalArgumentException("Choose a Minecraft instance containing mods or existing total-debug data.");
        }
        return CompanionProfile.forGame(game);
    }
}
