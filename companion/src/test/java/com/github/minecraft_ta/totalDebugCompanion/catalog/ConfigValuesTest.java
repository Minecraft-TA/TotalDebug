package com.github.minecraft_ta.totalDebugCompanion.catalog;

import com.github.minecraft_ta.totaldebug.storage.PackCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigValuesTest {
    @TempDir Path directory;

    @Test
    void readsValuesByDottedKeyInTheFormDefaultsUse() throws Exception {
        Path file = this.directory.resolve("testmod-common.toml");
        Files.writeString(file, """
                #Widget behavior
                [widgets]
                \t#How fast widgets spin
                \tspeed = 9
                \tmode = "SLOW"
                \tnames = ["a", "b"]
                \tratio = 0.5
                """);

        ConfigValues values = ConfigValues.read(file);

        assertEquals(Map.of("widgets.speed", "9", "widgets.mode", "SLOW", "widgets.names", "[a, b]",
                "widgets.ratio", "0.5"), values.values());
        assertEquals(List.of(new PackCatalog.ConfigSection("widgets", "Widget behavior")), values.sections());
        assertEquals("How fast widgets spin", values.settings().getFirst().comment());
        assertEquals(4, values.settings().size());
    }

    @Test
    void reportsInvalidToml() throws Exception {
        Path file = this.directory.resolve("broken.toml");
        Files.writeString(file, "[widgets\nspeed = ");

        IOException failure = assertThrows(IOException.class, () -> ConfigValues.read(file));
        assertTrue(failure.getMessage().startsWith("broken.toml is not valid TOML"), failure.getMessage());
    }
}
