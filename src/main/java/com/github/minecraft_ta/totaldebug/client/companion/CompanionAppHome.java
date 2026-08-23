package com.github.minecraft_ta.totaldebug.client.companion;

import java.nio.file.Path;
import java.util.Map;

final class CompanionAppHome {
    private CompanionAppHome() {
    }

    static Path resolve() {
        return resolve(System.getenv());
    }

    static Path resolve(Map<String, String> environment) {
        String override = System.getProperty(CompanionLaunchContract.APP_HOME_PROPERTY);
        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath().normalize();
        }
        String localAppData = environment.get("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            return Path.of(localAppData).resolve("TotalDebugCompanion").toAbsolutePath().normalize();
        }
        String userHome = System.getProperty("user.home");
        if (userHome == null || userHome.isBlank()) {
            throw new IllegalStateException("No Companion app home is available");
        }
        return Path.of(userHome).resolve(".totaldebug-companion").toAbsolutePath().normalize();
    }
}
