package com.github.minecraft_ta.totalDebugCompanion.pack;

import com.github.minecraft_ta.totalDebugCompanion.resource.ArchiveEntrySource;
import com.github.minecraft_ta.totalDebugCompanion.resource.ContentSource;
import com.github.minecraft_ta.totalDebugCompanion.resource.LocalFileSource;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Where a file sits in a pack, what the running game reloads to use it, and whether its text is usable. */
public final class ResourcePaths {
    /** Reads JSON the way the game's {@code GsonHelper} does, with the reader's own leniency. */
    private static final TypeAdapter<JsonElement> ELEMENTS = new Gson().getAdapter(JsonElement.class);
    /** Data folders the game reads only when a world loads: its dynamic registries. */
    private static final Set<String> WORLD_LOAD_FOLDERS = Set.of("worldgen", "dimension", "dimension_type",
            "damage_type", "chat_type", "trim_pattern", "trim_material", "wolf_variant", "painting_variant",
            "banner_pattern", "enchantment", "enchantment_provider", "jukebox_song", "neoforge");

    /** What makes the running game use an edited resource. */
    public enum Apply {
        /** A language reload, about a second. */
        LANGUAGE,
        /** A reload of every client resource. */
        RESOURCES,
        /** A reload of the server's data, as {@code /reload} does. */
        DATA,
        /** Loading the world again, which reads its registries. */
        WORLD_LOAD
    }

    private ResourcePaths() {
    }

    /**
     * The pack path of a file, such as {@code assets/ns/models/block/slab.json}, when it is a resource: an entry of a
     * mod or pack archive, or a file under a folder that holds a pack or a mod.
     */
    public static Optional<String> of(ContentSource source) {
        return switch (source) {
            case ArchiveEntrySource entry -> resource(entry.entryName());
            case LocalFileSource file -> ofFile(file.path());
            default -> Optional.empty();
        };
    }

    private static Optional<String> ofFile(Path file) {
        Path normalized = file.toAbsolutePath().normalize();
        for (int index = normalized.getNameCount() - 3; index >= 0; index--) {
            String name = normalized.getName(index).toString();
            if (!name.equals("assets") && !name.equals("data")) continue;
            // A pack's root holds pack.mcmeta; a folder at the top of the file system has no root above it.
            if (index == 0) continue;
            Path root = normalized.getRoot().resolve(normalized.subpath(0, index));
            if (!Files.isRegularFile(root.resolve("pack.mcmeta")) && !Files.isDirectory(root.resolve("META-INF"))) continue;
            return resource(root.relativize(normalized).toString().replace('\\', '/'));
        }
        return Optional.empty();
    }

    private static Optional<String> resource(String path) {
        String[] parts = path.split("/", 3);
        if (parts.length < 3 || !(parts[0].equals("assets") || parts[0].equals("data")) || parts[1].isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(path);
    }

    /** What makes the running game use the resource at {@code path}. */
    public static Apply apply(String path) {
        String[] parts = path.split("/", 4);
        if (parts[0].equals("assets")) {
            return parts.length > 3 && parts[2].equals("lang") && path.endsWith(".json") ? Apply.LANGUAGE : Apply.RESOURCES;
        }
        return parts.length > 3 && WORLD_LOAD_FOLDERS.contains(parts[2]) ? Apply.WORLD_LOAD : Apply.DATA;
    }

    /**
     * Checks text the game parses as JSON, the way it parses it: strictly, except language files, which the game reads
     * leniently. A language file must be one object whose values are text, or lists of components as NeoForge allows.
     * Returns the problem, with its line when the parser names one, or empty.
     */
    public static Optional<String> check(String path, String text) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".json") && !lower.endsWith(".mcmeta")) return Optional.empty();
        boolean language = apply(path) == Apply.LANGUAGE;
        JsonElement parsed;
        try {
            JsonReader reader = new JsonReader(new StringReader(text));
            reader.setLenient(language);
            parsed = ELEMENTS.read(reader);
        } catch (IOException | JsonParseException invalid) {
            Throwable cause = invalid instanceof JsonParseException && invalid.getCause() != null ? invalid.getCause() : invalid;
            return Optional.of("Not valid JSON: " + cause.getMessage());
        }
        if (parsed.isJsonNull()) return Optional.of("Not valid JSON: the file holds no value");
        if (language) {
            if (!parsed.isJsonObject()) return Optional.of("A language file holds one object of translations");
            for (Map.Entry<String, JsonElement> entry : parsed.getAsJsonObject().entrySet()) {
                JsonElement value = entry.getValue();
                if (!value.isJsonPrimitive() && !value.isJsonArray()) {
                    return Optional.of("The translation of " + entry.getKey() + " is not text or a list of components");
                }
            }
        }
        return Optional.empty();
    }
}
