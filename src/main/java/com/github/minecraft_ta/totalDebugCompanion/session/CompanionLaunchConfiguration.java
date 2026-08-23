package com.github.minecraft_ta.totalDebugCompanion.session;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

public record CompanionLaunchConfiguration(Path appHome) {
    public CompanionLaunchConfiguration {
        appHome = Objects.requireNonNull(appHome, "appHome").toAbsolutePath().normalize();
    }

    public static CompanionLaunchConfiguration parse(String[] arguments, Map<String, String> environment) {
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(environment, "environment");
        if (arguments.length == 0) {
            return new CompanionLaunchConfiguration(defaultAppHome(environment));
        }
        if (arguments.length != 2 || !CompanionLaunchContract.APP_HOME_ARGUMENT.equals(arguments[0])) {
            throw new IllegalArgumentException("Expected no arguments or --app-home <path>");
        }
        return new CompanionLaunchConfiguration(Path.of(arguments[1]));
    }

    public Path descriptorFile() {
        return this.appHome.resolve(CompanionLaunchContract.INSTANCE_DESCRIPTOR_FILE_NAME);
    }

    public Path keyFile() {
        return this.appHome.resolve(CompanionLaunchContract.INSTANCE_KEY_FILE_NAME);
    }

    public Path lockFile() {
        return this.appHome.resolve(CompanionLaunchContract.INSTANCE_LOCK_FILE_NAME);
    }

    public Path profileFile() {
        return this.appHome.resolve(CompanionLaunchContract.PROFILE_FILE_NAME);
    }

    private static Path defaultAppHome(Map<String, String> environment) {
        String override = System.getProperty(CompanionLaunchContract.APP_HOME_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        String localAppData = environment.get("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            return Path.of(localAppData).resolve("TotalDebugCompanion");
        }
        String userHome = System.getProperty("user.home");
        if (userHome == null || userHome.isBlank()) {
            throw new IllegalArgumentException("No Companion app home is available");
        }
        return Path.of(userHome).resolve(".totaldebug-companion");
    }
}
