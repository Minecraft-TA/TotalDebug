package com.github.minecraft_ta.totalDebugCompanion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionAppDataDirectoryTest {
    @TempDir
    Path appHome;

    @Test
    void cleanScriptHomeDoesNotPersistTheInternalBaseClass() throws Exception {
        CompanionApp.setupDataDirectories(this.appHome, true);

        assertTrue(Files.isDirectory(this.appHome.resolve("scripts")));
        assertTrue(Files.isDirectory(this.appHome.resolve("decompiled-files")));
        assertFalse(Files.exists(this.appHome.resolve("scripts/BaseScript.java")));
    }

}
