package com.github.minecraft_ta.totalDebugCompanion.catalog;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.UnmodifiableCommentedConfig;
import com.electronwill.nightconfig.core.io.ParsingException;
import com.electronwill.nightconfig.core.io.ParsingMode;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.electronwill.nightconfig.toml.TomlParser;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The values in a TOML configuration file by dotted key, in {@link PackCatalog.ConfigSetting#display(Object)} form so
 * they compare with captured defaults, and each value as the file writes it. A file without a captured specification
 * is described by its own tables and comments instead. {@code literals} is empty when the text cannot be edited in
 * place.
 */
public record ConfigValues(String text, Map<String, String> values, Map<String, String> literals,
                           List<PackCatalog.ConfigSection> sections, List<PackCatalog.ConfigSetting> settings) {
    public static final long MAX_FILE_BYTES = 4L * 1024 * 1024;

    public ConfigValues {
        values = Map.copyOf(values);
        literals = Map.copyOf(literals);
        sections = List.copyOf(sections);
        settings = List.copyOf(settings);
    }

    /** Reads a configuration file. Blocking; not on the Swing thread. */
    public static ConfigValues read(Path file) throws IOException {
        long size = Files.size(file);
        if (size > MAX_FILE_BYTES) {
            throw new IOException(file.getFileName() + " has " + size + " bytes; the limit is " + MAX_FILE_BYTES);
        }
        return parse(Files.readString(file, StandardCharsets.UTF_8), file.getFileName().toString());
    }

    /** Reads a configuration file's text; {@code name} names the file in a parse error. */
    public static ConfigValues parse(String text, String name) throws IOException {
        // Keys keep the file's order, which is the order the mod declared its settings in.
        CommentedConfig config = TomlFormat.newConfig(LinkedHashMap::new);
        try {
            new TomlParser().parse(new StringReader(text), config, ParsingMode.REPLACE);
        } catch (ParsingException exception) {
            throw new IOException(name + " is not valid TOML: " + exception.getMessage(), exception);
        }
        Map<String, String> values = new LinkedHashMap<>();
        List<PackCatalog.ConfigSection> sections = new ArrayList<>();
        List<PackCatalog.ConfigSetting> settings = new ArrayList<>();
        walk(config, "", values, sections, settings);
        Map<String, String> literals;
        try {
            literals = TomlText.of(text).literals();
        } catch (IllegalArgumentException notEditable) {
            literals = Map.of();
        }
        return new ConfigValues(text, values, literals, sections, settings);
    }

    /**
     * The value a TOML literal such as {@code 5} or {@code ["a", "b"]} stands for.
     *
     * @throws IllegalArgumentException when {@code literal} is not a TOML value
     */
    public static Object value(String literal) {
        CommentedConfig config = TomlFormat.newConfig(LinkedHashMap::new);
        try {
            new TomlParser().parse(new StringReader("value = " + literal + "\n"), config, ParsingMode.REPLACE);
        } catch (ParsingException exception) {
            throw new IllegalArgumentException(exception.getMessage(), exception);
        }
        if (config.size() != 1 || !config.contains("value")) throw new IllegalArgumentException("Not one TOML value: " + literal);
        return config.get("value");
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
