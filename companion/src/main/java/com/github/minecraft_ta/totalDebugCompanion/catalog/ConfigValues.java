package com.github.minecraft_ta.totalDebugCompanion.catalog;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.UnmodifiableCommentedConfig;
import com.electronwill.nightconfig.core.io.ParsingException;
import com.electronwill.nightconfig.core.io.ParsingMode;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.electronwill.nightconfig.toml.TomlParser;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The values in a TOML configuration file by dotted key, in {@link PackCatalog.ConfigSetting#display(Object)} form so
 * they compare with captured defaults. A file without a captured specification is described by its own tables and
 * comments instead.
 */
public record ConfigValues(Map<String, String> values, List<PackCatalog.ConfigSection> sections,
                           List<PackCatalog.ConfigSetting> settings) {
    public static final long MAX_FILE_BYTES = 4L * 1024 * 1024;

    public ConfigValues {
        values = Map.copyOf(values);
        sections = List.copyOf(sections);
        settings = List.copyOf(settings);
    }

    /** Reads a configuration file. Blocking; not on the Swing thread. */
    public static ConfigValues read(Path file) throws IOException {
        long size = Files.size(file);
        if (size > MAX_FILE_BYTES) {
            throw new IOException(file.getFileName() + " has " + size + " bytes; the limit is " + MAX_FILE_BYTES);
        }
        // Keys keep the file's order, which is the order the mod declared its settings in.
        CommentedConfig config = TomlFormat.newConfig(LinkedHashMap::new);
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            new TomlParser().parse(reader, config, ParsingMode.REPLACE);
        } catch (ParsingException exception) {
            throw new IOException(file.getFileName() + " is not valid TOML: " + exception.getMessage(), exception);
        }
        Map<String, String> values = new LinkedHashMap<>();
        List<PackCatalog.ConfigSection> sections = new ArrayList<>();
        List<PackCatalog.ConfigSetting> settings = new ArrayList<>();
        walk(config, "", values, sections, settings);
        return new ConfigValues(values, sections, settings);
    }

    private static void walk(UnmodifiableCommentedConfig level, String prefix, Map<String, String> values,
                             List<PackCatalog.ConfigSection> sections, List<PackCatalog.ConfigSetting> settings) {
        for (UnmodifiableCommentedConfig.Entry entry : level.entrySet()) {
            String key = prefix + entry.getKey();
            String comment = entry.getComment() == null ? "" : entry.getComment().strip();
            Object value = entry.getValue();
            if (value instanceof UnmodifiableCommentedConfig nested) {
                sections.add(new PackCatalog.ConfigSection(key, comment));
                walk(nested, key + ".", values, sections, settings);
            } else {
                String display = PackCatalog.ConfigSetting.display(value);
                values.put(key, display);
                settings.add(new PackCatalog.ConfigSetting(key, comment, "", "", List.of(), PackCatalog.Restart.NONE));
            }
        }
    }
}
