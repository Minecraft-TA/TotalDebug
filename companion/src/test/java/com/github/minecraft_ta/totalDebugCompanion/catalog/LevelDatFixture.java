package com.github.minecraft_ta.totalDebugCompanion.catalog;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * Writes a world's {@code level.dat} as the game does: gzipped NBT under a named root. Values are bytes, ints, longs,
 * strings, lists of strings and maps for compounds.
 */
public final class LevelDatFixture {
    private LevelDatFixture() {
    }

    /** A world like the game saves on its first day, with two rules and two datapacks, named {@code name}. */
    public static Map<String, Object> world(String name) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("LevelName", name);
        data.put("GameType", 0);
        data.put("Difficulty", (byte) 2);
        data.put("DifficultyLocked", (byte) 0);
        data.put("hardcore", (byte) 0);
        data.put("allowCommands", (byte) 1);
        data.put("DayTime", 30_000L);
        data.put("raining", (byte) 1);
        data.put("thundering", (byte) 0);
        data.put("SpawnX", 21);
        data.put("SpawnY", 77);
        data.put("SpawnZ", -28);
        data.put("LastPlayed", 1_790_000_000_000L);
        data.put("Version", Map.of("Name", "1.21.1"));
        data.put("WorldGenSettings", Map.of("seed", 8_757_790_292_842_126_093L));
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("keepInventory", "true");
        rules.put("doDaylightCycle", "false");
        rules.put("randomTickSpeed", "3");
        data.put("GameRules", rules);
        data.put("DataPacks", Map.of("Enabled", List.of("vanilla", "mod_data", "file/Tweaks"),
                "Disabled", List.of("bundle", "mod/testmod:data/testmod/datapacks/extra")));
        return data;
    }

    /** A folder datapack named {@code name} in {@code world}'s {@code datapacks} folder. */
    public static Path datapack(Path world, String name) throws IOException {
        Path pack = Files.createDirectories(world.resolve("datapacks").resolve(name));
        Files.writeString(pack.resolve("pack.mcmeta"), "{\"pack\":{\"pack_format\":48,\"description\":\"" + name + "\"}}");
        return pack;
    }

    /** Writes {@code data} as the world's data into {@code world}'s {@code level.dat}. */
    public static Path write(Path world, Map<String, Object> data) throws IOException {
        Files.createDirectories(world);
        Path file = world.resolve("level.dat");
        try (OutputStream stream = Files.newOutputStream(file);
             DataOutputStream output = new DataOutputStream(new GZIPOutputStream(stream))) {
            output.writeByte(10);
            output.writeUTF("");
            writeEntry(output, "Data", data);
            output.writeByte(0);
        }
        return file;
    }

    private static void writeEntry(DataOutputStream output, String name, Object value) throws IOException {
        output.writeByte(type(value));
        output.writeUTF(name);
        writePayload(output, value);
    }

    private static int type(Object value) {
        return switch (value) {
            case Byte ignored -> 1;
            case Integer ignored -> 3;
            case Long ignored -> 4;
            case String ignored -> 8;
            case List<?> ignored -> 9;
            case Map<?, ?> ignored -> 10;
            default -> throw new IllegalArgumentException("No NBT type for " + value);
        };
    }

    private static void writePayload(DataOutputStream output, Object value) throws IOException {
        switch (value) {
            case Byte number -> output.writeByte(number);
            case Integer number -> output.writeInt(number);
            case Long number -> output.writeLong(number);
            case String text -> output.writeUTF(text);
            case List<?> list -> {
                output.writeByte(list.isEmpty() ? 0 : type(list.getFirst()));
                output.writeInt(list.size());
                for (Object item : list) writePayload(output, item);
            }
            case Map<?, ?> compound -> {
                for (Map.Entry<?, ?> entry : compound.entrySet()) writeEntry(output, (String) entry.getKey(), entry.getValue());
                output.writeByte(0);
            }
            default -> throw new IllegalArgumentException("No NBT type for " + value);
        }
    }
}
