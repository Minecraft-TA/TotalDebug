package com.github.minecraft_ta.totaldebug.client.catalog;

import com.github.minecraft_ta.totaldebug.storage.PackCatalog;
import com.github.minecraft_ta.totaldebug.storage.RuntimeInventory;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Normalization of captured catalog values that does not need a running game. */
public final class PackCatalogEntries {
    private PackCatalogEntries() {
    }

    /** Maps every mod id to the inventory module containing it; module ids join their mod ids with {@code +}. */
    public static Map<String, String> moduleByModId(Collection<RuntimeInventory.RuntimeModule> modules) {
        Map<String, String> byModId = new TreeMap<>();
        for (RuntimeInventory.RuntimeModule module : modules) {
            if (module.kind() == RuntimeInventory.ModuleKind.LIBRARY || module.kind() == RuntimeInventory.ModuleKind.JAVA_RUNTIME) {
                continue;
            }
            for (String modId : module.id().split(Pattern.quote("+"))) {
                byModId.putIfAbsent(modId, module.id());
            }
        }
        return Map.copyOf(byModId);
    }

    /** The item's model, or empty when it is the conventional {@code ns:item/path} model Companion derives itself. */
    static String model(String itemId, String model) {
        int separator = itemId.indexOf(':');
        String conventional = itemId.substring(0, separator) + ":item/" + itemId.substring(separator + 1);
        return model.equals(conventional) ? "" : model;
    }

    static List<String> authors(Object value) {
        if (value instanceof Collection<?> values) {
            return values.stream().map(String::valueOf).map(String::strip).filter(author -> !author.isEmpty()).toList();
        }
        if (value instanceof String text && !text.isBlank()) {
            return List.of(text.strip());
        }
        return List.of();
    }

    static Optional<PackCatalog.Dependency> dependency(String modId, String type, String versionRange, String side) {
        if (!PackCatalog.isModId(modId)) {
            return Optional.empty();
        }
        String range = versionRange.strip();
        return Optional.of(new PackCatalog.Dependency(modId, PackCatalog.DependencyType.valueOf(type),
                range.isEmpty() || range.equals("[,)") ? "" : range, PackCatalog.Side.valueOf(side)));
    }

    static PackCatalog.ConfigType configType(String type) {
        return PackCatalog.ConfigType.valueOf(type.toUpperCase(Locale.ROOT));
    }
}
