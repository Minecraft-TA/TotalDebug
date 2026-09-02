package com.github.minecraft_ta.totaldebug.storage;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/** Per-user application files. Instance data never belongs below this root. */
public record AppPaths(Path home) {
    public static final String HOME_PROPERTY = "totaldebug.companionAppHome";

    public AppPaths {
        home = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
    }

    public static AppPaths defaults(Map<String, String> environment) {
        String override = System.getProperty(HOME_PROPERTY);
        if (override != null && !override.isBlank()) {
            return new AppPaths(Path.of(override));
        }
        String localAppData = environment.get("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            return new AppPaths(Path.of(localAppData).resolve("TotalDebugCompanion"));
        }
        String userHome = System.getProperty("user.home");
        if (userHome == null || userHome.isBlank()) {
            throw new IllegalArgumentException("No Companion application home is available");
        }
        return new AppPaths(Path.of(userHome).resolve(".totaldebug-companion"));
    }

    public Path settings() { return home.resolve("settings.json"); }
    public Path profile() { return home.resolve(CompanionLaunchContract.PROFILE_FILE_NAME); }
    public Path run() { return home.resolve("run").resolve("companion"); }
    public Path instanceLock() { return run().resolve(CompanionLaunchContract.INSTANCE_LOCK_FILE_NAME); }
    public Path instanceDescriptor() { return run().resolve(CompanionLaunchContract.INSTANCE_DESCRIPTOR_FILE_NAME); }
    public Path instanceKey() { return run().resolve(CompanionLaunchContract.INSTANCE_KEY_FILE_NAME); }
    public Path mcpEndpoint() { return run().resolve("mcp-endpoint.json"); }
    public Path launchCache() { return home.resolve("cache").resolve("apps"); }
    public Path mcpCache() { return home.resolve("cache").resolve("mcp"); }
    public Path logs() { return home.resolve("logs"); }
}
