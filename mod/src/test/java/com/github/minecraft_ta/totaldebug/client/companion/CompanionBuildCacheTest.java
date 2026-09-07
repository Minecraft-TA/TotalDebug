package com.github.minecraft_ta.totaldebug.client.companion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class CompanionBuildCacheTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void installsBuildsSideBySideByContentHash() throws Exception {
        Path firstSource = Files.write(this.temporaryDirectory.resolve("first.jar"), new byte[]{1, 2, 3});
        Path secondSource = Files.write(this.temporaryDirectory.resolve("second.jar"), new byte[]{4, 5, 6});
        Path appHome = this.temporaryDirectory.resolve("app-home");

        Path first = stage(appHome, firstSource);
        Path second = stage(appHome, secondSource);

        assertNotEquals(first, second);
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(first));
        assertArrayEquals(new byte[]{4, 5, 6}, Files.readAllBytes(second));
        assertEquals(first, stage(appHome, firstSource));
    }

    @Test
    void stagesNewBytesAfterTheMutableDevelopmentJarIsRebuilt() throws Exception {
        Path developmentJar = Files.write(this.temporaryDirectory.resolve("TotalDebugCompanion.jar"), new byte[]{1});
        Path appHome = this.temporaryDirectory.resolve("app-home");

        Path firstLaunch = stage(appHome, developmentJar);
        Files.write(developmentJar, new byte[]{2});
        Path secondLaunch = stage(appHome, developmentJar);

        assertNotEquals(firstLaunch, secondLaunch);
        assertArrayEquals(new byte[]{1}, Files.readAllBytes(firstLaunch));
        assertArrayEquals(new byte[]{2}, Files.readAllBytes(secondLaunch));
    }
    private static Path stage(Path home, Path source) throws Exception {
        try (var lease = com.github.minecraft_ta.totaldebug.storage.LaunchCache.stage(
                new com.github.minecraft_ta.totaldebug.storage.AppPaths(home), source)) {
            return lease.path();
        }
    }
}
