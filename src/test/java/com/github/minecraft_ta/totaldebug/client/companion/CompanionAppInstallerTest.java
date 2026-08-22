package com.github.minecraft_ta.totaldebug.client.companion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionAppInstallerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void extractsThePinnedDistributionLayout() throws Exception {
        byte[] archive = zip(
                "TotalDebugCompanion.jar", "jar",
                "bin/java.exe", "java"
        );

        CompanionAppInstaller.extractZip(new ByteArrayInputStream(archive), this.temporaryDirectory);

        assertEquals("jar", Files.readString(this.temporaryDirectory.resolve("TotalDebugCompanion.jar")));
        assertEquals("java", Files.readString(this.temporaryDirectory.resolve("bin/java.exe")));
    }

    @Test
    void rejectsArchiveEntriesOutsideTheInstallationDirectory() throws Exception {
        byte[] archive = zip("../outside.txt", "unsafe");
        Path outside = this.temporaryDirectory.getParent().resolve("outside.txt");

        assertThrows(
                IOException.class,
                () -> CompanionAppInstaller.extractZip(new ByteArrayInputStream(archive), this.temporaryDirectory)
        );
        assertFalse(Files.exists(outside));
    }

    @Test
    void developmentOverrideUsesTheExactConfiguredJarAndJava() throws Exception {
        Path developmentJar = Files.writeString(this.temporaryDirectory.resolve("companion.jar"), "jar");
        Path developmentJava = Path.of(
                System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java"
        ).toAbsolutePath().normalize();
        String oldJar = System.getProperty(CompanionAppInstaller.DEV_JAR_PROPERTY);
        String oldJava = System.getProperty(CompanionAppInstaller.DEV_JAVA_PROPERTY);
        try {
            System.setProperty(CompanionAppInstaller.DEV_JAR_PROPERTY, developmentJar.toString());
            System.setProperty(CompanionAppInstaller.DEV_JAVA_PROPERTY, developmentJava.toString());

            CompanionInstallation installation = new CompanionAppInstaller(
                    this.temporaryDirectory.resolve("app")
            ).resolveOrInstall();

            assertEquals(developmentJar.toAbsolutePath().normalize(), installation.companionJar());
            assertEquals(developmentJava, installation.javaExecutable());
        } finally {
            restoreProperty(CompanionAppInstaller.DEV_JAR_PROPERTY, oldJar);
            restoreProperty(CompanionAppInstaller.DEV_JAVA_PROPERTY, oldJava);
        }
    }

    @Test
    void rejectsAPartialDevelopmentOverride() {
        String oldJar = System.getProperty(CompanionAppInstaller.DEV_JAR_PROPERTY);
        String oldJava = System.getProperty(CompanionAppInstaller.DEV_JAVA_PROPERTY);
        try {
            System.setProperty(CompanionAppInstaller.DEV_JAR_PROPERTY, this.temporaryDirectory.resolve("app.jar").toString());
            System.clearProperty(CompanionAppInstaller.DEV_JAVA_PROPERTY);

            IOException exception = assertThrows(
                    IOException.class,
                    () -> new CompanionAppInstaller(this.temporaryDirectory.resolve("app")).resolveOrInstall()
            );
            assertTrue(exception.getMessage().contains("must be configured together"));
        } finally {
            restoreProperty(CompanionAppInstaller.DEV_JAR_PROPERTY, oldJar);
            restoreProperty(CompanionAppInstaller.DEV_JAVA_PROPERTY, oldJava);
        }
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }

    private static byte[] zip(String... namesAndContents) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (int index = 0; index < namesAndContents.length; index += 2) {
                zip.putNextEntry(new ZipEntry(namesAndContents[index]));
                zip.write(namesAndContents[index + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
