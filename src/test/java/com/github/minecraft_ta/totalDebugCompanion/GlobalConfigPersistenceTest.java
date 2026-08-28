package com.github.minecraft_ta.totalDebugCompanion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.awt.Rectangle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GlobalConfig} is a process wide singleton, so every test here sets the values it asserts on
 * rather than relying on pristine defaults.
 */
class GlobalConfigPersistenceTest {

    private static final String SETTINGS_FILE = "companion-ui-settings.json";

    @Test
    void roundTripsSettingsThroughDisk(@TempDir Path dataDirectory) {
        GlobalConfig config = GlobalConfig.getInstance();
        config.loadFrom(dataDirectory);

        config.setThemeId("islands-light");
        config.setEditorFontSize(21f);
        config.setUiFontSize(15f);
        config.setDebuggerWindowBounds(new Rectangle(120, 80, 1100, 620));
        config.setDebuggerWatches(List.of("player", "level.gameTime", "player"));
        GlobalConfig.PersistedBreakpoint persistedBreakpoint = new GlobalConfig.PersistedBreakpoint(
                "decompiled:///net/minecraft/world/level/block/Block.java",
                "net.minecraft.world.level.block.Block",
                42,
                44,
                "net.minecraft.world.level.block.Block",
                "tick",
                "()V",
                "state != null",
                "3",
                true
        );
        config.setDebuggerBreakpoints("runtime-a", List.of(persistedBreakpoint));
        config.setDebuggerBreakpointsMuted(true);
        config.setBreakOnCaughtExceptions(true);
        config.setBreakOnUncaughtExceptions(true);
        config.setDebuggerInlineValues(false);
        config.setAutomaticDebuggerPreviews(false);
        config.saveNow();

        assertTrue(Files.isRegularFile(dataDirectory.resolve(SETTINGS_FILE)));

        // Clobber the in-memory state, then reload from the file we just wrote.
        config.setThemeId("islands-dark");
        config.setEditorFontSize(14f);
        config.setUiFontSize(13f);
        config.setDebuggerWindowBounds(null);
        config.setDebuggerWatches(List.of());
        config.setDebuggerBreakpoints("runtime-a", List.of());
        config.setDebuggerBreakpointsMuted(false);
        config.setBreakOnCaughtExceptions(false);
        config.setBreakOnUncaughtExceptions(false);
        config.setDebuggerInlineValues(true);
        config.setAutomaticDebuggerPreviews(true);
        config.loadFrom(dataDirectory);

        assertEquals("islands-light", config.themeId());
        assertEquals(21f, config.editorFontSize());
        assertEquals(15f, config.uiFontSize());
        assertEquals(new Rectangle(120, 80, 1100, 620), config.debuggerWindowBounds());
        assertEquals(List.of("player", "level.gameTime"), config.debuggerWatches());
        assertEquals(List.of(persistedBreakpoint), config.debuggerBreakpoints("runtime-a"));
        assertTrue(config.debuggerBreakpointsMuted());
        assertTrue(config.breakOnCaughtExceptions());
        assertTrue(config.breakOnUncaughtExceptions());
        assertFalse(config.debuggerInlineValues());
        assertFalse(config.automaticDebuggerPreviews());
        config.setDebuggerInlineValues(true);
        config.setAutomaticDebuggerPreviews(true);
        config.setDebuggerWatches(List.of());
        config.setDebuggerBreakpoints("runtime-a", List.of());
        config.setDebuggerBreakpointsMuted(false);
    }

    @Test
    void keepsCurrentValuesWhenFileIsCorrupt(@TempDir Path dataDirectory) throws IOException {
        Files.writeString(dataDirectory.resolve(SETTINGS_FILE), "{not valid json", StandardCharsets.UTF_8);

        GlobalConfig config = GlobalConfig.getInstance();
        config.setThemeId("islands-dark");
        config.setEditorFontSize(14f);

        config.loadFrom(dataDirectory);

        assertEquals("islands-dark", config.themeId());
        assertEquals(14f, config.editorFontSize());
    }

    @Test
    void keepsCurrentValuesWhenFileIsMissing(@TempDir Path dataDirectory) {
        GlobalConfig config = GlobalConfig.getInstance();
        config.setThemeId("islands-dark");

        config.loadFrom(dataDirectory);

        assertEquals("islands-dark", config.themeId());
    }

    @Test
    void ignoresUnknownAndAbsentFields(@TempDir Path dataDirectory) throws IOException {
        Files.writeString(
                dataDirectory.resolve(SETTINGS_FILE),
                "{\"version\":3,\"theme\":\"islands-light\",\"somethingElse\":true}",
                StandardCharsets.UTF_8
        );

        GlobalConfig config = GlobalConfig.getInstance();
        config.setEditorFontSize(17f);
        config.loadFrom(dataDirectory);

        assertEquals("islands-light", config.themeId());
        // absent in the file, so the in-memory value survives
        assertEquals(17f, config.editorFontSize());
    }

    @Test
    void clampsAbsurdFontSizes(@TempDir Path dataDirectory) throws IOException {
        Files.writeString(
                dataDirectory.resolve(SETTINGS_FILE),
                "{\"version\":3,\"editorFontSize\":9999.0,\"uiFontSize\":-4.0}",
                StandardCharsets.UTF_8
        );

        GlobalConfig config = GlobalConfig.getInstance();
        config.loadFrom(dataDirectory);

        assertEquals(GlobalConfig.MAX_FONT_SIZE, config.editorFontSize());
        assertEquals(GlobalConfig.MIN_FONT_SIZE, config.uiFontSize());
    }

    @Test
    void ignoresIncompatibleSettingsVersion(@TempDir Path dataDirectory) throws IOException {
        Files.writeString(
                dataDirectory.resolve(SETTINGS_FILE),
                "{\"version\":999,\"theme\":\"islands-light\",\"editorFontSize\":22.0}",
                StandardCharsets.UTF_8
        );

        GlobalConfig config = GlobalConfig.getInstance();
        config.setThemeId("islands-dark");
        config.setEditorFontSize(14f);
        config.loadFrom(dataDirectory);

        assertEquals("islands-dark", config.themeId());
        assertEquals(14f, config.editorFontSize());
    }
}
