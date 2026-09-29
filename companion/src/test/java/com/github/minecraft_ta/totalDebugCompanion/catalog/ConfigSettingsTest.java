package com.github.minecraft_ta.totalDebugCompanion.catalog;

import com.github.minecraft_ta.totalDebugCompanion.change.ChangePipeline;
import com.github.minecraft_ta.totalDebugCompanion.change.Effect;
import com.github.minecraft_ta.totalDebugCompanion.game.GameLocations;
import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigSettingsTest {
    private static final PackCatalog.ConfigSetting SPEED = new PackCatalog.ConfigSetting("widgets.speed", "", "4", "1 ~ 16",
            List.of(), PackCatalog.Restart.NONE);
    private static final PackCatalog.ConfigSetting MODE = new PackCatalog.ConfigSetting("widgets.mode", "", "\"FAST\"", "",
            List.of("FAST", "SLOW"), PackCatalog.Restart.NONE);

    @TempDir Path directory;

    @Test
    void aSettingIsWrittenInPlaceRecordedAndRevertedThroughThePipeline() throws Exception {
        Path file = file("[widgets]\n\t# How fast\n\tspeed = 4\n\tmode = \"FAST\"\n");
        ChangeRecord record = ChangeRecord.inMemory();
        ConfigSettings settings = ConfigSettingsFixture.of(GameLocations.of(this.directory, false), record);

        ConfigSettings.Saved saved = settings.set(target(file, SPEED), "4", "9").get(5, TimeUnit.SECONDS);
        assertEquals("[widgets]\n\t# How fast\n\tspeed = 9\n\tmode = \"FAST\"\n", Files.readString(file), "nothing else of the file changes");
        assertEquals(Effect.GAME_STARTS, saved.changed().getFirst().effect());
        assertEquals("4", settings.original(file, "widgets.speed"), "the value before the first edit");

        settings.set(target(file, SPEED), "9", "4").get(5, TimeUnit.SECONDS);
        assertNull(settings.original(file, "widgets.speed"), "set back, the setting is no longer edited");
    }

    @Test
    void aSettingChangedInItsFileSinceItWasShownIsLeftAlone() throws Exception {
        Path file = file("[widgets]\n\tspeed = 7\n");
        ChangeRecord record = ChangeRecord.inMemory();
        ConfigSettings settings = ConfigSettingsFixture.of(GameLocations.of(this.directory, false), record);

        ExecutionException refused = assertThrows(ExecutionException.class,
                () -> settings.set(target(file, SPEED), "4", "9").get(5, TimeUnit.SECONDS));
        assertInstanceOf(ChangePipeline.Stale.class, refused.getCause());
        assertEquals("widgets.speed in testmod-common.toml changed in its file since Companion read it", refused.getCause().getMessage());
        assertEquals("[widgets]\n\tspeed = 7\n", Files.readString(file), "such as a change by the game, or an undo of an edit the file no longer holds");
        assertEquals(0, record.size());
    }

    @Test
    void aSavedTextIsOneChangeOfTheSettingsItChangesAndAsksWhenTheFileChanged() throws Exception {
        String base = "[widgets]\n\tspeed = 4\n\tmode = \"FAST\"\n";
        Path file = file(base);
        ChangeRecord record = ChangeRecord.inMemory();
        ConfigSettings settings = ConfigSettingsFixture.of(GameLocations.of(this.directory, false), record);
        String edited = "[widgets]\n\t# Faster\n\tspeed = 8\n\tmode = \"SLOW\"\n";

        ConfigSettings.Saved saved = settings.saveText(fileTarget(file), List.of(SPEED, MODE), base, edited, false).get(5, TimeUnit.SECONDS);
        assertEquals(edited, Files.readString(file), "the text as written, comments too");
        assertEquals("2 settings saved", saved.message());
        assertEquals("4", settings.original(file, "widgets.speed"));
        assertEquals("\"FAST\"", settings.original(file, "widgets.mode"));

        Files.writeString(file, "[widgets]\n\tspeed = 10\n\tmode = \"SLOW\"\n");
        ExecutionException conflict = assertThrows(ExecutionException.class, () -> settings.saveText(fileTarget(file),
                List.of(SPEED, MODE), edited, base, false).get(5, TimeUnit.SECONDS));
        assertInstanceOf(ConfigSettings.ConflictException.class, conflict.getCause(), "the text changed on disk since it was opened");
        settings.saveText(fileTarget(file), List.of(SPEED, MODE), edited, base, true).get(5, TimeUnit.SECONDS);
        assertEquals(base, Files.readString(file), "overwritten when the user chose to");
    }

    @Test
    void aTextThatChangesNoSettingIsWrittenWithoutARecord() throws Exception {
        String base = "[widgets]\n\tspeed = 4\n";
        Path file = file(base);
        ChangeRecord record = ChangeRecord.inMemory();
        ConfigSettings settings = ConfigSettingsFixture.of(GameLocations.of(this.directory, false), record);

        assertEquals("testmod-common.toml saved", settings.saveText(fileTarget(file), List.of(SPEED), base,
                "# Tuned\n" + base, false).get(5, TimeUnit.SECONDS).message());
        assertEquals("# Tuned\n" + base, Files.readString(file));
        assertEquals(0, record.size());
    }

    private Path file(String text) throws Exception {
        Path file = Files.createDirectories(this.directory.resolve("config")).resolve("testmod-common.toml");
        Files.writeString(file, text);
        return file;
    }

    private static ConfigSettings.Target target(Path file, PackCatalog.ConfigSetting setting) {
        return new ConfigSettings.Target("testmod", "testmod-common.toml", file, PackCatalog.ConfigType.COMMON, setting);
    }

    private static ConfigSettings.FileTarget fileTarget(Path file) {
        return new ConfigSettings.FileTarget("testmod", "testmod-common.toml", file, PackCatalog.ConfigType.COMMON);
    }
}
