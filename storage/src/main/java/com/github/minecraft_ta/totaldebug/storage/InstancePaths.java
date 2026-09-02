package com.github.minecraft_ta.totaldebug.storage;

import java.nio.file.Path;
import java.util.Objects;

/** Storage for one instance, independent of its current game connection. */
public record InstancePaths(Path home) {
    public static final String WORKSPACE_PROPERTY = "totaldebug.workspaceRoot";

    public InstancePaths {
        home = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
    }

    public static InstancePaths forGame(Path gameDirectory) {
        String workspace = System.getProperty(WORKSPACE_PROPERTY);
        Path root = workspace == null || workspace.isBlank() ? gameDirectory : Path.of(workspace);
        return new InstancePaths(root.resolve("total-debug"));
    }

    /** Installation is game-local even when authored state uses an explicit dev workspace. */
    public static Path installationDirectory(Path gameDirectory) {
        return Objects.requireNonNull(gameDirectory).toAbsolutePath().normalize().resolve("total-debug").resolve("companion-app");
    }

    public Path scripts() { return home.resolve("scripts"); }
    public Path state() { return home.resolve("state.json"); }
    public Path cache() { return home.resolve("cache"); }
    public Path runtime() { return cache().resolve("runtime"); }
    public Path inventory() { return runtime().resolve("inventory.json"); }
    public Path sources() { return runtime().resolve("sources"); }
    public Path index() { return runtime().resolve("index.jindex"); }
    public Path decompiled() { return cache().resolve("decompiled"); }

}
