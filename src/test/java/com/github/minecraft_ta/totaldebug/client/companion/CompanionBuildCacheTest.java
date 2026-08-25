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

        Path first = CompanionAppClient.stageLaunchJar(appHome, firstSource);
        Path second = CompanionAppClient.stageLaunchJar(appHome, secondSource);

        assertNotEquals(first, second);
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(first));
        assertArrayEquals(new byte[]{4, 5, 6}, Files.readAllBytes(second));
        assertEquals(first, CompanionAppClient.stageLaunchJar(appHome, firstSource));
    }

    @Test
    void stagesNewBytesAfterTheMutableDevelopmentJarIsRebuilt() throws Exception {
        Path developmentJar = Files.write(this.temporaryDirectory.resolve("TotalDebugCompanion.jar"), new byte[]{1});
        Path appHome = this.temporaryDirectory.resolve("app-home");

        Path firstLaunch = CompanionAppClient.stageLaunchJar(appHome, developmentJar);
        Files.write(developmentJar, new byte[]{2});
        Path secondLaunch = CompanionAppClient.stageLaunchJar(appHome, developmentJar);

        assertNotEquals(firstLaunch, secondLaunch);
        assertArrayEquals(new byte[]{1}, Files.readAllBytes(firstLaunch));
        assertArrayEquals(new byte[]{2}, Files.readAllBytes(secondLaunch));
    }
}
