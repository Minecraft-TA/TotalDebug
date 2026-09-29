package com.github.minecraft_ta.totalDebugCompanion.catalog;

import com.github.minecraft_ta.totalDebugCompanion.change.ChangeLabels;
import com.github.minecraft_ta.totalDebugCompanion.change.Effect;
import com.github.minecraft_ta.totalDebugCompanion.navigation.ModTab;
import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totalDebugCompanion.storage.ChangeRecord;
import com.github.minecraft_ta.totaldebug.storage.PackCatalog;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Configuration settings on the Changes page: each setting by its name, where its mod and file are, and its literal now
 * and before; a setting the running game still waits to use says what for.
 */
public final class ConfigLabels implements ChangeLabels {
    private final ConfigSettings settings;

    public ConfigLabels(ConfigSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    @Override
    public String tab() {
        return "Configuration";
    }

    @Override
    public boolean covers(ChangeRecord.Target target) {
        return target instanceof ChangeRecord.Setting;
    }

    @Override
    public Rows rows(List<ChangeRecord.Change> changes, CatalogIndex index) {
        this.settings.refresh();
        Map<Path, List<ChangeRecord.Change>> byFile = new LinkedHashMap<>();
        for (ChangeRecord.Change change : changes) byFile.computeIfAbsent(setting(change).file(), file -> new ArrayList<>()).add(change);
        List<Row> rows = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        for (Map.Entry<Path, List<ChangeRecord.Change>> file : byFile.entrySet()) {
            ConfigValues values;
            try {
                values = ConfigValues.read(file.getKey());
            } catch (IOException unreadable) {
                problems.add(unreadable.getMessage());
                continue;
            }
            for (ChangeRecord.Change change : file.getValue()) {
                ChangeRecord.Setting target = setting(change);
                String literal = values.literals().get(target.setting());
                if (literal == null) {
                    problems.add(target.setting() + " is no longer in " + target.file().getFileName());
                    continue;
                }
                this.settings.record().observed(target, literal, ConfigEdit::sameValue);
                if (ConfigEdit.sameValue(literal, change.original())) continue;
                PackCatalog.ConfigSetting described = described(index, target);
                Effect pending = this.settings.pending(target.file(), target.setting());
                rows.add(new Row(change, target.setting(), where(index, target), literal,
                        change.original(), pending == null ? "" : Character.toUpperCase(pending.description().charAt(0)) + pending.description().substring(1),
                        opens(target), new Actions("Show in Configuration",
                        change.original().length() > 24 ? "Revert" : "Revert to " + change.original(), "", described.name())));
            }
        }
        rows.sort(Comparator.comparing((Row row) -> row.where().toLowerCase(Locale.ROOT)).thenComparing(Row::name));
        return new Rows(rows, problems);
    }

    @Override
    public CompletableFuture<String> revert(List<ChangeRecord.Change> changes, CatalogIndex index) {
        return ChangeLabels.each(changes, change -> {
            ChangeRecord.Setting target = setting(change);
            ConfigSettings.Target write = new ConfigSettings.Target(target.modId(), target.fileName(), target.file(),
                    type(index, target), described(index, target));
            return this.settings.set(write, change.current(), change.original()).thenApply(saved -> "");
        }, change -> setting(change).setting());
    }

    private static ChangeRecord.Setting setting(ChangeRecord.Change change) {
        return (ChangeRecord.Setting) change.target();
    }

    private static PackCatalog.ConfigFile file(CatalogIndex index, ChangeRecord.Setting target) {
        if (index == null) return null;
        return index.mod(target.modId()).flatMap(mod -> mod.configs().stream()
                .filter(file -> file.fileName().equals(target.fileName())).findFirst()).orElse(null);
    }

    /** The captured setting, or one without a description when the catalog does not have it. */
    private static PackCatalog.ConfigSetting described(CatalogIndex index, ChangeRecord.Setting target) {
        PackCatalog.ConfigFile file = file(index, target);
        if (file != null) {
            for (PackCatalog.ConfigSetting setting : file.settings()) {
                if (setting.path().equals(target.setting())) return setting;
            }
        }
        return new PackCatalog.ConfigSetting(target.setting(), "", "", "", List.of(), PackCatalog.Restart.NONE);
    }

    private static PackCatalog.ConfigType type(CatalogIndex index, ChangeRecord.Setting target) {
        PackCatalog.ConfigFile file = file(index, target);
        return file == null ? PackCatalog.ConfigType.COMMON : file.type();
    }

    /**
     * The Configuration tab on the setting's file; for a world's server configuration, only where that tab shows that
     * world's copy, the current world's.
     */
    private NavigationTarget opens(ChangeRecord.Setting target) {
        Path world = world(target);
        if (world != null && !world.toAbsolutePath().normalize().equals(this.settings.location().read().currentWorld())) return null;
        return new NavigationTarget.ModPage(target.modId(), ModTab.CONFIGURATION, target.fileName());
    }

    /** The world whose {@code serverconfig} holds the setting's file, or null. */
    private static Path world(ChangeRecord.Setting target) {
        Path serverconfig = target.file().getParent();
        return serverconfig != null && serverconfig.getFileName() != null && serverconfig.getFileName().toString().equals("serverconfig")
                ? serverconfig.getParent() : null;
    }

    /** The mod and file of a setting, and the world a server configuration file is in. */
    private String where(CatalogIndex index, ChangeRecord.Setting target) {
        String where = modName(index, target.modId()) + ", " + target.fileName();
        Path world = world(target);
        return world == null || world.getFileName() == null ? where : where + ", " + world.getFileName();
    }

    private static String modName(CatalogIndex index, String modId) {
        return index == null ? modId : index.mod(modId).map(PackCatalog.Mod::name).orElse(modId);
    }
}
