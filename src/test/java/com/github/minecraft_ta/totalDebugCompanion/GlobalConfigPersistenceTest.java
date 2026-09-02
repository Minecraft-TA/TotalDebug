package com.github.minecraft_ta.totalDebugCompanion;

import com.github.minecraft_ta.totaldebug.storage.AppPaths;
import com.github.minecraft_ta.totaldebug.storage.JsonFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.awt.Rectangle;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class GlobalConfigPersistenceTest {
    @TempDir Path home;

    @Test
    void roundTripsOnlyGlobalPreferences() throws Exception {
        GlobalConfig config = new GlobalConfig();
        config.loadFrom(this.home);
        config.setThemeId("islands-light");
        config.setEditorFontSize(21);
        config.setUiFontSize(15);
        config.setDebuggerWindowBounds(new Rectangle(120, 80, 1100, 620));
        config.setDebuggerInlineValues(false);
        config.setAutomaticDebuggerPreviews(false);
        config.saveNow();

        GlobalConfig restored = new GlobalConfig();
        restored.loadFrom(this.home);
        assertEquals("islands-light", restored.themeId());
        assertEquals(21, restored.editorFontSize());
        assertEquals(15, restored.uiFontSize());
        assertEquals(new Rectangle(120, 80, 1100, 620), restored.debuggerWindowBounds());
        assertFalse(restored.debuggerInlineValues());
        assertFalse(restored.automaticDebuggerPreviews());
        var json = JsonFiles.read(new AppPaths(this.home).settings());
        assertFalse(json.has("debuggerWatches"));
        assertFalse(json.has("debuggerBreakpoints"));
        assertFalse(json.has("history"));
    }

    @Test
    void rejectsCorruptAndUnsupportedSettingsWithoutOverwritingThem() throws Exception {
        Path file = new AppPaths(this.home).settings();
        for (String invalid : java.util.List.of("{bad", "{\"version\":999}", "{\"version\":1}")) {
            Files.writeString(file, invalid);
            assertThrows(IOException.class, () -> new GlobalConfig().loadFrom(this.home));
            assertEquals(invalid, Files.readString(file));
        }
    }

    @Test
    void usesDefaultsOnlyForAMissingFile() throws Exception {
        GlobalConfig config = new GlobalConfig();
        config.loadFrom(this.home);
        assertEquals("islands-dark", config.themeId());
        assertEquals(14, config.editorFontSize());
        assertFalse(Files.exists(new AppPaths(this.home).settings()));
        config.setEditorFontSize(9999);
        config.setUiFontSize(-4);
        assertEquals(GlobalConfig.MAX_FONT_SIZE, config.editorFontSize());
        assertEquals(GlobalConfig.MIN_FONT_SIZE, config.uiFontSize());
        config.saveNow();
    }
}
