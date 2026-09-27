package com.github.minecraft_ta.totalDebugCompanion.catalog;

import com.github.minecraft_ta.totaldebug.protocol.nbt.NbtData;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;

/**
 * The current world as its {@code level.dat} saved it: the world the game has open, or the one played last while none
 * is. The game saves {@code level.dat} when it autosaves and when the world closes, so a running game may be ahead of
 * it. Blocking.
 */
public final class CurrentWorld {
    private static final int MAX_LEVEL_BYTES = 16 * 1024 * 1024;

    /** Where a player appears when they have no bed or anchor. */
    public record Spawn(int x, int y, int z) {
    }

    /** Whether the game applies a datapack. */
    public enum PackState {
        ENABLED,
        DISABLED,
        /** In the world's {@code datapacks} folder but in neither list: the game enables it when it loads the world next. */
        NEW
    }

    /** A datapack of the world, with its file when it is in the world's {@code datapacks} folder, or null. */
    public record Datapack(String id, PackState state, Path file) {
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
                        Map<String, String> gameRules, List<Datapack> datapacks) {
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
        Path level = world.resolve("level.dat");
        if (!Files.exists(level) && Files.isRegularFile(world.resolve("level.dat_old"))) level = world.resolve("level.dat_old");
        FileTime saved = Files.getLastModifiedTime(level);
        NbtData.CompoundTag data = compound(root(level), "Data");
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
                rules, datapacks(world, strings(packs, "Enabled"), strings(packs, "Disabled")));
    }

    /**
     * The world's datapacks. The game names a pack in the world's {@code datapacks} folder {@code file/} and its file
     * name, and takes a folder with a {@code pack.mcmeta} or a file ending in {@code .zip}.
     */
    private static List<Datapack> datapacks(Path world, List<String> enabled, List<String> disabled) throws IOException {
        Map<String, Path> files = new TreeMap<>();
        Path folder = world.resolve("datapacks");
        if (Files.isDirectory(folder)) {
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder)) {
                for (Path entry : entries) {
                    String name = entry.getFileName().toString();
                    boolean pack = Files.isDirectory(entry) ? Files.isRegularFile(entry.resolve("pack.mcmeta"))
                            : Files.isRegularFile(entry) && name.endsWith(".zip");
                    if (pack) files.put("file/" + name, entry);
                }
            }
        }
        List<Datapack> packs = new ArrayList<>();
        for (String id : enabled.reversed()) packs.add(new Datapack(id, PackState.ENABLED, files.remove(id)));
        for (String id : disabled) packs.add(new Datapack(id, PackState.DISABLED, files.remove(id)));
        files.forEach((id, file) -> packs.add(new Datapack(id, PackState.NEW, file)));
        return packs;
    }

    /** The root compound of a gzipped NBT file, whose root is written with a name the reader does not take. */
    private static NbtData.CompoundTag root(Path file) throws IOException {
        byte[] named;
        try (InputStream input = new GZIPInputStream(Files.newInputStream(file))) {
            named = input.readNBytes(MAX_LEVEL_BYTES + 1);
        }
        if (named.length > MAX_LEVEL_BYTES) throw new IOException(file + " is larger than " + MAX_LEVEL_BYTES + " bytes");
        if (named.length < 3 || named[0] != 10) throw new IOException(file + " does not start with a compound");
        int nameLength = ((named[1] & 0xFF) << 8) | (named[2] & 0xFF);
        if (3 + nameLength > named.length) throw new IOException(file + " ends in the root's name");
        byte[] unnamed = new byte[named.length - 2 - nameLength];
        unnamed[0] = named[0];
        System.arraycopy(named, 3 + nameLength, unnamed, 1, unnamed.length - 1);
        try {
            if (NbtData.read(unnamed) instanceof NbtData.CompoundTag root) return root;
        } catch (IllegalArgumentException unreadable) {
            throw new IOException(file + " could not be read: " + unreadable.getMessage(), unreadable);
        }
        throw new IOException(file + " does not hold a compound");
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
