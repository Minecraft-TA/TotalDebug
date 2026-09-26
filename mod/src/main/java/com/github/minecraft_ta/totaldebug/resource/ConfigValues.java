package com.github.minecraft_ta.totaldebug.resource;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.protocol.message.ConfigValueResultPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.SetConfigValuePayload;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.config.IConfigSpec;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.config.ModConfigs;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Sets a configuration value in the running game's memory the way NeoForge applies a reloaded file: the loaded
 * configuration takes the value, the specification drops its cached values and the mod receives
 * {@link ModConfigEvent.Reloading}. The file is not written, so the value lasts until NeoForge reads the file again,
 * or until Companion disconnects, which puts the files' values back.
 */
public final class ConfigValues {
    /** The settings tried in memory, by configuration file name. */
    private static final Map<String, Set<List<String>>> TRIED = new HashMap<>();

    private ConfigValues() {
    }

    /** Applies a request and answers with the value before and after, or why it stayed. */
    public static ConfigValueResultPayload apply(SetConfigValuePayload request) {
        ModConfig config = ModConfigs.getFileMap().get(request.fileName());
        if (config == null) return failed(request, request.fileName() + " is not a configuration loaded by this game");
        if (!(config.getSpec() instanceof ModConfigSpec spec)) return failed(request, request.fileName() + " is not a NeoForge configuration");
        IConfigSpec.ILoadedConfig loaded = config.getLoadedConfig();
        if (loaded == null) return failed(request, request.fileName() + " is not loaded now, such as a server configuration without an open world");
        List<String> path = Arrays.asList(request.setting().split("\\."));
        Object described = spec.getSpec().get(path);
        if (!(described instanceof ModConfigSpec.ValueSpec valueSpec)) return failed(request, request.setting() + " is not a setting of " + request.fileName());
        Object value;
        try {
            CommentedConfig parsed = new TomlParser().parse("value = " + request.literal());
            value = convert(parsed.get("value"), valueSpec.getDefault());
        } catch (RuntimeException invalid) {
            return failed(request, request.literal() + " is not a TOML value: " + invalid.getMessage());
        }
        if (!valueSpec.test(value)) return failed(request, request.literal() + " is not allowed for " + request.setting());
        Object previous = loaded.config().get(path);
        loaded.config().set(path, value);
        reloaded(config, spec, loaded);
        synchronized (TRIED) {
            TRIED.computeIfAbsent(request.fileName(), ignored -> new HashSet<>()).add(path);
        }
        return new ConfigValueResultPayload(request.requestId(), String.valueOf(previous), String.valueOf(value), "");
    }

    /** Puts every tried setting back to the value its file holds and tells the mods. Client thread only. */
    public static void restoreFiles() {
        Map<String, Set<List<String>>> tried;
        synchronized (TRIED) {
            tried = new HashMap<>(TRIED);
            TRIED.clear();
        }
        tried.forEach((fileName, paths) -> {
            ModConfig config = ModConfigs.getFileMap().get(fileName);
            if (config == null || !(config.getSpec() instanceof ModConfigSpec spec)) return;
            IConfigSpec.ILoadedConfig loaded = config.getLoadedConfig();
            if (loaded == null) return;
            CommentedConfig file;
            try (Reader reader = Files.newBufferedReader(config.getFullPath(), StandardCharsets.UTF_8)) {
                file = new TomlParser().parse(reader);
            } catch (IOException | RuntimeException unreadable) {
                TotalDebug.LOGGER.warn("Could not read {} to put back the values Companion tried: {}", config.getFullPath(), unreadable.toString());
                return;
            }
            for (List<String> path : paths) {
                if (!(spec.getSpec().get(path) instanceof ModConfigSpec.ValueSpec valueSpec)) continue;
                Object value = file.contains(path) ? convert(file.get(path), valueSpec.getDefault()) : valueSpec.getDefault();
                loaded.config().set(path, value);
            }
            reloaded(config, spec, loaded);
        });
    }

    /** Drops the specification's cached values and sends the mod the reload event, as NeoForge does after a reload. */
    private static void reloaded(ModConfig config, ModConfigSpec spec, IConfigSpec.ILoadedConfig loaded) {
        spec.acceptConfig(loaded);
        Optional<? extends ModContainer> container = ModList.get().getModContainerById(config.getModId());
        container.ifPresent(mod -> mod.acceptEvent(new ModConfigEvent.Reloading(config)));
    }

    /** Numbers take the type of the setting's default, as TOML does not say whether 12 is an int, long or double. */
    private static Object convert(Object value, Object defaultValue) {
        if (value instanceof Number number && defaultValue instanceof Number) {
            return switch (defaultValue) {
                case Integer ignored -> number.intValue();
                case Long ignored -> number.longValue();
                case Double ignored -> number.doubleValue();
                case Float ignored -> number.floatValue();
                default -> value;
            };
        }
        if (defaultValue instanceof Enum<?> && value instanceof String name) {
            for (Object constant : defaultValue.getClass().getEnumConstants()) {
                if (((Enum<?>) constant).name().equals(name)) return constant;
            }
        }
        return value;
    }

    private static ConfigValueResultPayload failed(SetConfigValuePayload request, String error) {
        return new ConfigValueResultPayload(request.requestId(), "", "", error);
    }
}
