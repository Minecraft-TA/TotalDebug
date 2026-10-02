package com.github.minecraft_ta.totalDebugCompanion;

import com.github.minecraft_ta.totalDebugCompanion.storage.JsonStateWriter;
import com.github.minecraft_ta.totaldebug.storage.AppPaths;
import com.github.minecraft_ta.totaldebug.storage.JsonFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.awt.Rectangle;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
        config.setInlineDiagnostics(false);
        config.setSidebarWidth("resource-categories", 260);
        config.setImageEditor("C:/Program Files/paint.net/paintdotnet.exe");
        config.saveNow();

        GlobalConfig restored = new GlobalConfig();
        restored.loadFrom(this.home);
        assertEquals("islands-light", restored.themeId());
        assertEquals(21, restored.editorFontSize());
        assertEquals(15, restored.uiFontSize());
        assertEquals(new Rectangle(120, 80, 1100, 620), restored.debuggerWindowBounds());
        assertFalse(restored.debuggerInlineValues());
        assertFalse(restored.automaticDebuggerPreviews());
        assertFalse(restored.inlineDiagnostics());
        assertEquals(260, restored.sidebarWidth("resource-categories"), "a dragged sidebar keeps its width across restarts");
        assertNull(restored.sidebarWidth("log-files"), "a sidebar never dragged starts at its default");
        assertEquals("C:/Program Files/paint.net/paintdotnet.exe", restored.imageEditor());
        restored.setImageEditor(" ");
        assertNull(restored.imageEditor(), "blank goes back to the system app");
        var json = JsonFiles.read(new AppPaths(this.home).settings());
        assertFalse(json.has("debuggerWatches"));
        assertFalse(json.has("debuggerBreakpoints"));
        assertFalse(json.has("history"));
    }

    @Test
    void rejectsCorruptAndUnsupportedSettingsWithoutOverwritingThem() throws Exception {
        Path file = new AppPaths(this.home).settings();
        for (String invalid : List.of("{bad", "{\"version\":999}", "{\"version\":1}",
                "{\"version\":1,\"theme\":\"islands-dark\",\"editorFontSize\":14,\"uiFontSize\":13,\"debuggerInlineValues\":true,"
                        + "\"automaticDebuggerPreviews\":true,\"sidebarWidths\":{\"log-files\":-3}}",
                "{\"version\":1,\"theme\":\"islands-dark\",\"editorFontSize\":14,\"uiFontSize\":13,\"debuggerInlineValues\":true,"
                        + "\"automaticDebuggerPreviews\":true,\"sidebarWidths\":{\"log-files\":2147483648}}")) {
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
        assertTrue(config.inlineDiagnostics());
        assertNull(config.imageEditor(), "the system app for images until one is chosen");
        assertFalse(Files.exists(new AppPaths(this.home).settings()));
        config.setEditorFontSize(9999);
        config.setUiFontSize(-4);
        assertEquals(GlobalConfig.MAX_FONT_SIZE, config.editorFontSize());
        assertEquals(GlobalConfig.MIN_FONT_SIZE, config.uiFontSize());
        config.saveNow();
    }

    @Test
    void aSettingChangesWhileTheSettingsAreBeingSaved() throws Exception {
        CountDownLatch paused = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        GlobalConfig config = new GlobalConfig((file, snapshot) -> {
            paused.countDown();
            try {
                assertTrue(released.await(10, TimeUnit.SECONDS), "the paused save was released");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException(exception);
            }
            JsonStateWriter.Write.FILE.write(file, snapshot);
        });
        config.loadFrom(this.home);
        config.setThemeId("islands-light");
        CompletableFuture<Void> saving = CompletableFuture.runAsync(() -> {
            try {
                config.saveNow();
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        });
        assertTrue(paused.await(5, TimeUnit.SECONDS), "the save started");

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> config.setEditorFontSize(21),
                "a setting does not wait for the file being written");

        released.countDown();
        saving.get(5, TimeUnit.SECONDS);
        config.saveNow();
        GlobalConfig restored = new GlobalConfig();
        restored.loadFrom(this.home);
        assertEquals(21, restored.editorFontSize());
    }
}
