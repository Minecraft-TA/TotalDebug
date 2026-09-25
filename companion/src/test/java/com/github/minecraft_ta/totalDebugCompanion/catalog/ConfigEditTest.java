package com.github.minecraft_ta.totalDebugCompanion.catalog;

import com.github.minecraft_ta.totaldebug.storage.PackCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigEditTest {
    private static final String FILE = """
            #Widget behavior
            [widgets]
            \t#How fast widgets spin
            \tspeed = 9 # spins per tick
            \tmode = "SLOW"
            \tnames = [
            \t  "a, ]", # first
            \t  'b#',
            \t]
            \tratio = 0.5
            \t"quoted key" = true

            [widgets.nested]
            \tdepth = 3
            [[entries]]
            \tname = "not addressable"
            """;

    @TempDir Path directory;

    @Test
    void findsEveryValueAsTheFileWritesIt() {
        TomlText toml = TomlText.of(FILE);

        assertEquals(Map.of("widgets.speed", "9", "widgets.mode", "\"SLOW\"", "widgets.names",
                "[\n\t  \"a, ]\", # first\n\t  'b#',\n\t]", "widgets.ratio", "0.5", "widgets.quoted key", "true",
                "widgets.nested.depth", "3"), toml.literals());
        assertNull(toml.literal("entries.name"));
    }

    @Test
    void replacingAValueKeepsEverythingElse() {
        String edited = TomlText.of(FILE).with("widgets.speed", "12");

        assertEquals(FILE.replace("speed = 9 # spins", "speed = 12 # spins"), edited);
        assertThrows(IllegalArgumentException.class, () -> TomlText.of(FILE).with("widgets.missing", "1"));
    }

    @Test
    void refusesTextThatIsNotToml() {
        IllegalArgumentException problem = assertThrows(IllegalArgumentException.class, () -> TomlText.of("a = \"open\nb = 1"));
        assertTrue(problem.getMessage().contains("line 1"), problem.getMessage());
    }

    @Test
    void typedValuesBecomeLiteralsOfTheSettingsKind() {
        PackCatalog.ConfigSetting speed = setting("1 ~ 16", List.of());
        assertEquals("12", ConfigEdit.literal("9", speed, " 12 "));
        assertEquals("Accepts 1 to 16", assertThrows(IllegalArgumentException.class,
                () -> ConfigEdit.literal("9", speed, "17")).getMessage());
        assertEquals("Enter a whole number", assertThrows(IllegalArgumentException.class,
                () -> ConfigEdit.literal("9", speed, "1.5")).getMessage());

        // A float stays a float, so NeoForge does not reset it.
        assertEquals("2.0", ConfigEdit.literal("0.5", setting("0.0 ~ 4.0", List.of()), "2"));
        assertEquals("Accepts at least 1", assertThrows(IllegalArgumentException.class,
                () -> ConfigEdit.literal("5", setting("> 1", List.of()), "0")).getMessage());

        assertEquals("false", ConfigEdit.literal("true", null, "False"));
        assertThrows(IllegalArgumentException.class, () -> ConfigEdit.literal("true", null, "yes"));

        PackCatalog.ConfigSetting mode = setting("", List.of("FAST", "SLOW"));
        assertEquals("\"FAST\"", ConfigEdit.literal("\"SLOW\"", mode, "fast"));
        assertEquals("Accepts FAST, SLOW", assertThrows(IllegalArgumentException.class,
                () -> ConfigEdit.literal("\"SLOW\"", mode, "MEDIUM")).getMessage());
        assertEquals("\"say \\\"hi\\\"\\\\\"", ConfigEdit.literal("'x'", null, "say \"hi\"\\"));

        assertEquals("[\"a\", \"b\"]", ConfigEdit.literal("[]", null, "[\"a\", \"b\"]"));
        assertThrows(IllegalArgumentException.class, () -> ConfigEdit.literal("[]", null, "[a, b]"));
        assertThrows(IllegalArgumentException.class, () -> ConfigEdit.literal("[]", null, "[1]\nextra = 2"));
        assertFalse(ConfigEdit.editable("1979-05-27T07:32:00Z"));
        assertFalse(ConfigEdit.editable(null));
    }

    @Test
    void rangesReadAsWordsAndTypeLimitsAreNoBound() {
        assertEquals("at least 1", ConfigEdit.readableRange("> 1"));
        assertEquals("at least 1", ConfigEdit.readableRange("1 ~ 9223372036854775807"));
        assertEquals("at most 5", ConfigEdit.readableRange("-2147483648 ~ 5"));
        assertEquals("0.1 to 4000000", ConfigEdit.readableRange("0.1 ~ 4000000.0"));
        assertEquals("", ConfigEdit.readableRange("-1.7976931348623157E308 ~ 1.7976931348623157E308"));
    }

    @Test
    void writingChangesOnlyTheValueAndKeepsLineEndings() throws Exception {
        Path file = this.directory.resolve("testmod-common.toml");
        String windows = FILE.replace("\n", "\r\n");
        Files.writeString(file, windows, StandardCharsets.UTF_8);

        assertEquals("\"SLOW\"", ConfigEdit.write(file, "widgets.mode", "\"FAST\""));

        assertEquals(windows.replace("mode = \"SLOW\"", "mode = \"FAST\""), Files.readString(file, StandardCharsets.UTF_8));
        assertEquals("FAST", ConfigValues.read(file).values().get("widgets.mode"));
    }

    @Test
    void writingReportsASettingTheFileLacks() throws Exception {
        Path file = this.directory.resolve("testmod-common.toml");
        Files.writeString(file, FILE);

        IOException missing = assertThrows(IOException.class, () -> ConfigEdit.write(file, "widgets.missing", "1"));

        assertEquals("widgets.missing is not in testmod-common.toml", missing.getMessage());
        assertEquals(FILE, Files.readString(file));
    }

    @Test
    void anEditedTextIsCheckedLikeNeoForgeChecksTheFile() {
        List<PackCatalog.ConfigSetting> settings = List.of(
                new PackCatalog.ConfigSetting("widgets.speed", "", "4", "1 ~ 16", List.of(), PackCatalog.Restart.NONE),
                new PackCatalog.ConfigSetting("widgets.ratio", "", "0.5", "0.0 ~ 1.0", List.of(), PackCatalog.Restart.NONE),
                new PackCatalog.ConfigSetting("widgets.mode", "", "FAST", "", List.of("FAST", "SLOW"), PackCatalog.Restart.WORLD));
        String saved = """
                [widgets]
                \t#How fast
                \tspeed = 9
                \tratio = 0.5
                \tmode = "SLOW"
                """;

        ConfigEdit.checkText(saved, saved.replace("speed = 9", "speed = 12").replace("#How fast", "#Spins"), settings);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> ConfigEdit.checkText(saved, saved.replace("\"SLOW\"", "\"SLOW"), settings)).getMessage().contains("line 5"));
        assertEquals("widgets.speed: Accepts 1 to 16", assertThrows(IllegalArgumentException.class,
                () -> ConfigEdit.checkText(saved, saved.replace("speed = 9", "speed = 20"), settings)).getMessage());
        assertEquals("widgets.ratio: Write a number with a decimal point, such as 2.0", assertThrows(IllegalArgumentException.class,
                () -> ConfigEdit.checkText(saved, saved.replace("ratio = 0.5", "ratio = 1"), settings)).getMessage());
        assertEquals("widgets.mode: Accepts FAST, SLOW", assertThrows(IllegalArgumentException.class,
                () -> ConfigEdit.checkText(saved, saved.replace("\"SLOW\"", "\"MEDIUM\""), settings)).getMessage());
        assertEquals("widgets.speed is missing; NeoForge would write its default back", assertThrows(IllegalArgumentException.class,
                () -> ConfigEdit.checkText(saved, saved.replace("\tspeed = 9\n", ""), settings)).getMessage());
        assertEquals("widgets.extra is not a setting of this file; NeoForge would remove it", assertThrows(IllegalArgumentException.class,
                () -> ConfigEdit.checkText(saved, saved + "\textra = 1\n", settings)).getMessage());

        List<ConfigEdit.TextChange> changes = ConfigEdit.changes(saved,
                saved.replace("speed = 9", "speed = 12").replace("\"SLOW\"", "'SLOW'"), settings);
        assertEquals(1, changes.size(), "the same string in other quotes is the same value");
        assertEquals(new ConfigEdit.TextChange(settings.getFirst(), "9", "12"), changes.getFirst());
    }

    private static PackCatalog.ConfigSetting setting(String range, List<String> allowed) {
        return new PackCatalog.ConfigSetting("widgets.value", "", "", range, allowed, PackCatalog.Restart.NONE);
    }
}
