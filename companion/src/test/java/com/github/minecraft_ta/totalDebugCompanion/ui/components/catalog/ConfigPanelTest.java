package com.github.minecraft_ta.totalDebugCompanion.ui.components.catalog;

import com.github.minecraft_ta.totalDebugCompanion.catalog.ConfigValues;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigPanelTest {
    @TempDir Path directory;

    private static final PackCatalog.ConfigFile FILE = new PackCatalog.ConfigFile("testmod-server.toml",
            PackCatalog.ConfigType.SERVER, null,
            List.of(new PackCatalog.ConfigSection("widgets", "Widget behavior")),
            List.of(new PackCatalog.ConfigSetting("widgets.speed", "How fast widgets spin", "4", "1 ~ 16", List.of(),
                            PackCatalog.Restart.NONE),
                    new PackCatalog.ConfigSetting("widgets.mode", "", "FAST", "", List.of("FAST", "SLOW"),
                            PackCatalog.Restart.WORLD),
                    new PackCatalog.ConfigSetting("enabled", "", "true", "", List.of(), PackCatalog.Restart.GAME)));

    @Test
    void rowsGroupSettingsUnderSectionsAndMarkChangedValues() {
        ConfigValues values = new ConfigValues(Map.of("widgets.speed", "9", "widgets.mode", "FAST", "enabled", "true"),
                List.of(), List.of());

        List<ConfigPanel.Row> rows = ConfigPanel.rows(FILE, values);

        assertEquals(List.of("widgets", "widgets.speed", "widgets.mode", "enabled"),
                rows.stream().map(ConfigPanel.Row::path).toList());
        assertEquals("Widget behavior", rows.getFirst().comment());
        assertTrue(rows.get(1).changed());
        assertFalse(rows.get(2).changed());
        assertEquals("FAST, SLOW", rows.get(2).accepts());
        assertEquals("1 to 16", rows.get(1).accepts());
        assertEquals(ConfigPanel.ValueKind.NUMBER, rows.get(1).kind());
        assertEquals(ConfigPanel.ValueKind.CHOICE, rows.get(2).kind());
        assertEquals(ConfigPanel.ValueKind.BOOLEAN, rows.get(3).kind());
        String speed = ConfigPanel.tooltip(rows.get(1));
        assertTrue(speed.contains("widgets.speed") && speed.contains("How fast widgets spin")
                && speed.contains("Accepts 1 to 16") && speed.contains(">4</font>"), speed);
        assertTrue(ConfigPanel.tooltip(rows.get(2)).contains("Takes effect after rejoining the world"));
    }

    @Test
    void rangesReadAsWordsAndTypeLimitsAreNoBound() {
        assertEquals("at least 1", ConfigPanel.readableRange("> 1"));
        assertEquals("at least 1", ConfigPanel.readableRange("1 ~ 9223372036854775807"));
        assertEquals("at most 5", ConfigPanel.readableRange("-2147483648 ~ 5"));
        assertEquals("0.1 to 4000000", ConfigPanel.readableRange("0.1 ~ 4000000.0"));
        assertEquals("", ConfigPanel.readableRange("-1.7976931348623157E308 ~ 1.7976931348623157E308"));
    }

    @Test
    void aMissingFileShowsTheDefaults() {
        List<ConfigPanel.Row> rows = ConfigPanel.rows(FILE, null);

        assertEquals("4", rows.get(1).value());
        assertFalse(rows.get(1).changed());
    }

    @Test
    void serverConfigurationsComeFromEachWorldNewestFirstThenTheDefaults() throws Exception {
        Path older = world("Old World", 1_000);
        Path newer = world("New World", 2_000);
        Path defaults = Files.createDirectories(this.directory.resolve("defaultconfigs")).resolve(FILE.fileName());
        Files.writeString(defaults, "");
        ConfigPanel panel = new ConfigPanel(this.directory, target -> { });

        assertEquals(List.of(new ConfigPanel.Source("New World", newer), new ConfigPanel.Source("Old World", older),
                new ConfigPanel.Source("New worlds", defaults)), panel.sources(FILE));
    }

    private Path world(String name, long modified) throws Exception {
        Path file = Files.createDirectories(this.directory.resolve("saves").resolve(name).resolve("serverconfig"))
                .resolve(FILE.fileName());
        Files.writeString(file, "");
        Files.setLastModifiedTime(file, FileTime.fromMillis(modified));
        return file;
    }
}
