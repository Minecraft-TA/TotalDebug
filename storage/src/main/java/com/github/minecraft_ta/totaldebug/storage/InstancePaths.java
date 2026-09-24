package com.github.minecraft_ta.totaldebug.storage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

/** Storage for one instance, independent of its current game connection. */
public record InstancePaths(Path home) {
    public InstancePaths {
        home = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
    }

    public static InstancePaths forGame(Path gameDirectory) {
        return new InstancePaths(gameDirectory.resolve("total-debug"));
    }

    public static String profileId(Path gameDirectory) {
        String identity = gameDirectory.toAbsolutePath().normalize().toString();
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows"))
            identity = identity.toLowerCase(Locale.ROOT);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) { throw new AssertionError(failure); }
    }

    /** Installation stays with the game instance. */
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
    public Path catalog() { return runtime().resolve("catalog.json"); }
    public Path previews() { return cache().resolve("inspection-previews"); }
    public Path decompiled() { return cache().resolve("decompiled"); }

}
