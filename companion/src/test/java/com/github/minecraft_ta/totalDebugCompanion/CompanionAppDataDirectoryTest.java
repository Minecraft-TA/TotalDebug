package com.github.minecraft_ta.totalDebugCompanion;

import com.github.minecraft_ta.totalDebugCompanion.session.CompanionLaunchConfiguration;
import com.github.minecraft_ta.totalDebugCompanion.session.ProjectDirectories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;

class CompanionAppDataDirectoryTest {
    @TempDir
    Path appHome;

    @Test
    void openingAndClosingAnEmptyInstanceDoesNotCreateData() throws Exception {
        Path game = Files.createDirectories(appHome.resolve("game"));
        Files.createDirectory(game.resolve("mods"));
        try (var app = new CompanionApplication(new CompanionLaunchConfiguration(appHome.resolve("application")), "test")) {
            app.openProject(ProjectDirectories.resolve(game)).join();
        }
        assertFalse(Files.exists(game.resolve("total-debug")));
    }

}
