package com.github.minecraft_ta.totaldebug.client.companion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
            List<CompanionStartupProgress> progress = new ArrayList<>();

            CompanionInstallation installation = new CompanionAppInstaller(
                    this.temporaryDirectory.resolve("app")
            ).resolveOrInstall(progress::add);

            assertEquals(developmentJar.toAbsolutePath().normalize(), installation.companionJar());
            assertEquals(List.of(), progress);
        } finally {
            restoreProperty(CompanionAppInstaller.DEV_JAR_PROPERTY, oldJar);
        }
    }

    @Test
    void clientConfigUsesTheMutableDevelopmentJar() throws Exception {
        Path developmentJar = Files.writeString(this.temporaryDirectory.resolve("configured-companion.jar"), "jar");
        String oldJar = System.getProperty(CompanionAppInstaller.DEV_JAR_PROPERTY);
        try {
            System.clearProperty(CompanionAppInstaller.DEV_JAR_PROPERTY);

            CompanionInstallation installation = new CompanionAppInstaller(
                    this.temporaryDirectory.resolve("app"),
                    developmentJar.toString()
            ).resolveOrInstall();

            assertEquals(developmentJar.toAbsolutePath().normalize(), installation.companionJar());
        } finally {
            restoreProperty(CompanionAppInstaller.DEV_JAR_PROPERTY, oldJar);
        }
    }

    @Test
    void systemPropertyOverridesTheClientConfigPath() throws Exception {
        Path configuredJar = Files.writeString(this.temporaryDirectory.resolve("configured.jar"), "configured");
        Path propertyJar = Files.writeString(this.temporaryDirectory.resolve("property.jar"), "property");
        String oldJar = System.getProperty(CompanionAppInstaller.DEV_JAR_PROPERTY);
        try {
            System.setProperty(CompanionAppInstaller.DEV_JAR_PROPERTY, propertyJar.toString());

            CompanionInstallation installation = new CompanionAppInstaller(
                    this.temporaryDirectory.resolve("app"),
                    configuredJar.toString()
            ).resolveOrInstall();

            assertEquals(propertyJar.toAbsolutePath().normalize(), installation.companionJar());
        } finally {
            restoreProperty(CompanionAppInstaller.DEV_JAR_PROPERTY, oldJar);
        }
    }

    @Test
    void usesTheSingleInstalledJarInsteadOfAnObsoleteVersionDirectory() throws Exception {
        String oldJar = System.getProperty(CompanionAppInstaller.DEV_JAR_PROPERTY);
        try {
            System.clearProperty(CompanionAppInstaller.DEV_JAR_PROPERTY);
            Path appDirectory = this.temporaryDirectory.resolve("app");
            Path installedJar = appDirectory.resolve("TotalDebugCompanion.jar");
            Files.createDirectories(appDirectory.resolve("2.0.0"));
            Files.writeString(installedJar, "current");
            Files.writeString(appDirectory.resolve("2.0.0/TotalDebugCompanion.jar"), "obsolete");

            CompanionInstallation installation = new CompanionAppInstaller(appDirectory).resolveOrInstall();

            assertEquals(installedJar.toAbsolutePath().normalize(), installation.companionJar());
            assertEquals("current", Files.readString(installedJar));
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

    @Test
    void downloadCopyReportsActualTransferredBytes() throws Exception {
        byte[] bytes = new byte[20_000];
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        List<Long> transferred = new ArrayList<>();

        CompanionAppInstaller.copyDownload(new ByteArrayInputStream(bytes), output, transferred::add);

        assertEquals(bytes.length, output.size());
        assertEquals(List.of(16_384L, 20_000L), transferred);
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}
