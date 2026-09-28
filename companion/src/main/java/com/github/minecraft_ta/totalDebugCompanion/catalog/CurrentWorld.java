package com.github.minecraft_ta.totalDebugCompanion.catalog;

import com.github.minecraft_ta.totaldebug.protocol.nbt.NbtData;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * The current world as its {@code level.dat} saved it: the world the game has open, or the one played last while none
 * is. The game saves {@code level.dat} when it autosaves and when the world closes, so a running game may be ahead of
 * it. Blocking.
 */
public final class CurrentWorld {
    /** Where a player appears when they have no bed or anchor. */
    public record Spawn(int x, int y, int z) {
    }

    /**
     * A world's saved state: its folder, whether the game has it open and when {@code level.dat} was saved, its name,
     * seed, game mode, difficulty and rules, its time and weather, its spawn, the version that saved it, and its
     * datapacks as the game's pack screen lists them: enabled ones with the highest first, then disabled ones, then new
     * ones.
     */
    public record Saved(Path directory, boolean open, FileTime saved, String name, Long seed, String gameMode,
                        String difficulty, boolean difficultyLocked, boolean hardcore, boolean commands, long dayTime,
                        boolean raining, boolean thundering, Spawn spawn, String version, Instant lastPlayed,
                        Map<String, String> gameRules, List<ListedPack> datapacks) {
        public Saved {
            gameRules = Collections.unmodifiableMap(new TreeMap<>(gameRules));
            datapacks = List.copyOf(datapacks);
        }

        /** The day the world is on, the first being day 1. */
        public long day() {
            return this.dayTime / 24_000 + 1;
        }

        /** The time of day on a clock: the day starts at 06:00 in Minecraft. */
        public String timeOfDay() {
            long ticks = Math.floorMod(this.dayTime, 24_000L);
            long hours = (ticks / 1_000 + 6) % 24;
            long minutes = ticks % 1_000 * 60 / 1_000;
            return "%02d:%02d".formatted(hours, minutes);
        }

        public String weather() {
            return this.thundering ? "Thunderstorm" : this.raining ? "Rain" : "Clear";
        }
    }

    /** Minecraft's datapacks that turn on a feature of their own. */
    private static final Map<String, String> BUILT_IN_FEATURES = Map.of("bundle", "minecraft:bundle",
            "trade_rebalance", "minecraft:trade_rebalance");

    private CurrentWorld() {
    }

    /** The current world's folder: the one the game has open, or the one played last. Empty without a world. */
    public static Optional<Path> directory(Path workspace) {
        Path open = Worlds.open(workspace);
        return Optional.ofNullable(open != null ? open : Worlds.lastPlayed(workspace));
    }

    /**
     * Reads a world's {@code level.dat}. The game saves it by renaming the last one to {@code level.dat_old} first, and
     * loads that one when {@code level.dat} is missing; so does this.
     */
    public static Saved read(Path world) throws IOException {
        Path level = LevelDat.file(world);
        FileTime saved = Files.getLastModifiedTime(level);
        NbtData.CompoundTag data = compound(LevelDat.read(level).tag(), "Data");
        if (data == null) throw new IOException(level + " holds no world data");
        NbtData.CompoundTag generation = compound(data, "WorldGenSettings");
        NbtData.CompoundTag version = compound(data, "Version");
        NbtData.CompoundTag packs = compound(data, "DataPacks");
        Map<String, String> rules = new TreeMap<>();
        NbtData.CompoundTag gameRules = compound(data, "GameRules");
        if (gameRules != null) {
            gameRules.entries().forEach((rule, value) -> {
                if (value instanceof NbtData.StringTag text) rules.put(rule, text.value());
            });
        }
        String name = string(data, "LevelName");
        long lastPlayed = number(data, "LastPlayed", 0);
        return new Saved(world, Worlds.isOpen(world), saved, name.isEmpty() ? world.getFileName().toString() : name,
                generation == null || !(generation.entries().get("seed") instanceof NbtData.LongTag seed) ? null : seed.value(),
                gameMode((int) number(data, "GameType", 0)), difficulty((int) number(data, "Difficulty", 2)),
                number(data, "DifficultyLocked", 0) != 0, number(data, "hardcore", 0) != 0, number(data, "allowCommands", 0) != 0,
                number(data, "DayTime", number(data, "Time", 0)), number(data, "raining", 0) != 0,
                number(data, "thundering", 0) != 0,
                new Spawn((int) number(data, "SpawnX", 0), (int) number(data, "SpawnY", 0), (int) number(data, "SpawnZ", 0)),
                version == null ? "" : string(version, "Name"), lastPlayed > 0 ? Instant.ofEpochMilli(lastPlayed) : saved.toInstant(),
                rules, datapacks(world, strings(packs, "Enabled"), strings(packs, "Disabled"), Set.copyOf(strings(data, "enabled_features"))));
    }

    /**
     * The world's datapacks. The game names a pack in the world's {@code datapacks} folder {@code file/} and its file
     * name (see {@link PackFolders}), and drops one whose folder or zip is gone when it loads the world. {@code features}
     * are the ones the world has, which a pack may request.
     */
    private static List<ListedPack> datapacks(Path world, List<String> enabled, List<String> disabled, Set<String> features)
            throws IOException {
        Map<String, Path> files = PackFolders.list(world.resolve("datapacks"));
        List<ListedPack> packs = new ArrayList<>();
        for (String id : enabled.reversed()) {
            if (!gone(id, files)) packs.add(listed(id, ListedPack.State.ENABLED, files.remove(id), features));
        }
        for (String id : disabled) {
            if (!gone(id, files)) packs.add(listed(id, ListedPack.State.DISABLED, files.remove(id), features));
        }
        files.forEach((id, file) -> packs.add(listed(id, ListedPack.State.NEW, file, features)));
        return packs;
    }

    /** A datapack with what the game allows for it: the mods' data is required, and the parts of it go with it. */
    private static ListedPack listed(String id, ListedPack.State state, Path file, Set<String> features) {
        Set<ListedPack.Rule> rules = EnumSet.noneOf(ListedPack.Rule.class);
        if (id.equals("mod_data")) rules.add(ListedPack.Rule.REQUIRED);
        if (id.startsWith("mod/")) rules.add(ListedPack.Rule.PART_OF_MODS);
        if (!features.containsAll(requested(id, file))) rules.add(ListedPack.Rule.MISSING_FEATURES);
        return new ListedPack(id, state, file, "", rules);
    }

    /** The features a datapack requests: Minecraft's feature packs their own, a pack of the folder those its metadata names. */
    private static List<String> requested(String id, Path file) {
        String builtIn = BUILT_IN_FEATURES.get(id);
        if (builtIn != null) return List.of(builtIn);
        if (file == null) return List.of();
        try {
            byte[] meta = PackFolders.read(file, "pack.mcmeta");
            if (meta == null || !(JsonParser.parseString(new String(meta, StandardCharsets.UTF_8)) instanceof JsonObject root)
                    || !(root.get("features") instanceof JsonObject section) || !(section.get("enabled") instanceof JsonArray list)) {
                return List.of();
            }
            List<String> requested = new ArrayList<>();
            for (JsonElement feature : list) {
                if (feature.isJsonPrimitive()) {
                    String name = feature.getAsString();
                    requested.add(name.contains(":") ? name : "minecraft:" + name);
                }
            }
            return requested;
        } catch (IOException | RuntimeException unreadable) {
            return List.of();
        }
    }

    private static boolean gone(String id, Map<String, Path> files) {
        return id.startsWith("file/") && !files.containsKey(id);
    }

    private static NbtData.CompoundTag compound(NbtData.CompoundTag parent, String key) {
        return parent != null && parent.entries().get(key) instanceof NbtData.CompoundTag child ? child : null;
    }

    private static String string(NbtData.CompoundTag parent, String key) {
        return parent != null && parent.entries().get(key) instanceof NbtData.StringTag text ? text.value() : "";
    }

    /** A whole number of any width, or {@code otherwise} when the tag is missing. */
    private static long number(NbtData.CompoundTag parent, String key, long otherwise) {
        if (parent == null) return otherwise;
        return switch (parent.entries().get(key)) {
            case NbtData.ByteTag value -> value.value();
            case NbtData.ShortTag value -> value.value();
            case NbtData.IntTag value -> value.value();
            case NbtData.LongTag value -> value.value();
            case null, default -> otherwise;
        };
    }

    private static List<String> strings(NbtData.CompoundTag parent, String key) {
        List<String> values = new ArrayList<>();
        if (parent != null && parent.entries().get(key) instanceof NbtData.ListTag list) {
            for (NbtData.Tag item : list.items()) {
                if (item instanceof NbtData.StringTag text) values.add(text.value());
            }
        }
        return values;
    }

    private static String gameMode(int id) {
        return switch (id) {
            case 1 -> "Creative";
            case 2 -> "Adventure";
            case 3 -> "Spectator";
            default -> "Survival";
        };
    }

    private static String difficulty(int id) {
        return switch (id) {
            case 0 -> "Peaceful";
            case 1 -> "Easy";
            case 3 -> "Hard";
            default -> "Normal";
        };
    }
}
