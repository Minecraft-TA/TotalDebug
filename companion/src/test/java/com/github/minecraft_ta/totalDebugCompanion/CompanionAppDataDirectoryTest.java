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
    void cleanScriptHomeDoesNotPersistGeneratedRuntimeSources() throws Exception {
        CompanionApplication.setupDataDirectories(this.appHome, true);

        assertTrue(Files.isDirectory(this.appHome.resolve("scripts")));
        assertFalse(Files.exists(this.appHome.resolve("decompiled-files")));
        assertFalse(Files.exists(this.appHome.resolve("scripts/BaseScript.java")));
        assertFalse(Files.exists(this.appHome.resolve("scripts/ScriptProgram.java")));
    }

}
