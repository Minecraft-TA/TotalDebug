package com.github.minecraft_ta.totaldebug.client.catalog;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * The sections and settings a NeoForge configuration specification declares: comments, defaults, accepted values and
 * what must restart after a change. Current values stay in the file, where Companion reads them.
 */
final class ConfigSpecs {
    record Spec(List<PackCatalog.ConfigSection> sections, List<PackCatalog.ConfigSetting> settings) {
    }

    private ConfigSpecs() {
    }

    static Spec of(ModConfig config) {
        List<PackCatalog.ConfigSection> sections = new ArrayList<>();
        List<PackCatalog.ConfigSetting> settings = new ArrayList<>();
        if (config.getSpec() instanceof ModConfigSpec spec) {
            walk(spec, spec.getSpec(), List.of(), sections, settings, config.getFileName());
        }
        return new Spec(sections, settings);
    }

    private static void walk(ModConfigSpec spec, UnmodifiableConfig level, List<String> path,
                             List<PackCatalog.ConfigSection> sections, List<PackCatalog.ConfigSetting> settings,
                             String fileName) {
        for (UnmodifiableConfig.Entry entry : level.entrySet()) {
            List<String> child = new ArrayList<>(path);
            child.add(entry.getKey());
            String key = String.join(".", child);
            Object value = entry.getValue();
            try {
                if (value instanceof UnmodifiableConfig nested) {
                    sections.add(new PackCatalog.ConfigSection(key,
                            PackCatalogEntries.configComment(spec.getLevelComment(child))));
                    walk(spec, nested, child, sections, settings, fileName);
                } else if (value instanceof ModConfigSpec.ValueSpec setting) {
                    settings.add(setting(key, setting));
                }
            } catch (RuntimeException exception) {
                TotalDebug.LOGGER.debug("Leaving {} of {} out of the pack catalog", key, fileName, exception);
            }
        }
    }

    private static PackCatalog.ConfigSetting setting(String key, ModConfigSpec.ValueSpec setting) {
        List<String> allowed = new ArrayList<>();
        if (setting.getClazz() != null && setting.getClazz().isEnum()) {
            for (Object constant : setting.getClazz().getEnumConstants()) {
                if (setting.test(constant)) allowed.add(((Enum<?>) constant).name());
            }
            if (allowed.isEmpty()) {
                for (Object constant : setting.getClazz().getEnumConstants()) allowed.add(((Enum<?>) constant).name());
            }
        }
        ModConfigSpec.Range<?> range = setting.getRange();
        return new PackCatalog.ConfigSetting(key, PackCatalogEntries.configComment(setting.getComment()),
                PackCatalog.ConfigSetting.display(setting.getDefault()), range == null ? "" : range.toString(),
                allowed, PackCatalog.Restart.valueOf(setting.restartType().name()));
    }
}
