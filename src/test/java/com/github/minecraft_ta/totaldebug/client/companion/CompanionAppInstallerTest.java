package com.github.minecraft_ta.totaldebug.client.companion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CompanionAppInstallerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void developmentOverrideUsesTheExactConfiguredJar() throws Exception {
        Path developmentJar = Files.writeString(this.temporaryDirectory.resolve("companion.jar"), "jar");
        String oldJar = System.getProperty(CompanionAppInstaller.DEV_JAR_PROPERTY);
        try {
            System.setProperty(CompanionAppInstaller.DEV_JAR_PROPERTY, developmentJar.toString());

            CompanionInstallation installation = new CompanionAppInstaller(
                    this.temporaryDirectory.resolve("app")
            ).resolveOrInstall();

            assertEquals(developmentJar.toAbsolutePath().normalize(), installation.companionJar());
        } finally {
            restoreProperty(CompanionAppInstaller.DEV_JAR_PROPERTY, oldJar);
        }
    }

    @Test
    void rejectsAMissingDevelopmentJar() {
        String oldJar = System.getProperty(CompanionAppInstaller.DEV_JAR_PROPERTY);
        Path missingJar = this.temporaryDirectory.resolve("missing.jar");
        try {
            System.setProperty(CompanionAppInstaller.DEV_JAR_PROPERTY, missingJar.toString());

            IOException exception = assertThrows(
                    IOException.class,
                    () -> new CompanionAppInstaller(this.temporaryDirectory.resolve("app")).resolveOrInstall()
            );
            assertEquals(
                    "Configured companion development JAR does not exist: " + missingJar.toAbsolutePath().normalize(),
                    exception.getMessage()
            );
        } finally {
            restoreProperty(CompanionAppInstaller.DEV_JAR_PROPERTY, oldJar);
        }
    }

    @Test
    void hashesTheExactJarBytes() throws Exception {
        Path jar = Files.writeString(this.temporaryDirectory.resolve("companion.jar"), "TotalDebugCompanion");

        assertEquals(
                "abbcb536b7001362a76775a1494ea745d0d65dc37544bbf85ac06071c17fe770",
                CompanionAppInstaller.sha256(jar)
        );
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}
