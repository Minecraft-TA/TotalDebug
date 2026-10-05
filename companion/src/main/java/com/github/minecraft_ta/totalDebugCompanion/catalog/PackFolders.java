package com.github.minecraft_ta.totalDebugCompanion.catalog;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.zip.ZipFile;

/**
 * Packs kept as folders or zip files, such as those in {@code resourcepacks/} or a world's {@code datapacks/}, and what
 * their {@code pack.mcmeta} says. Blocking.
 */
public final class PackFolders {
    /** Enough for a pack's {@code pack.mcmeta} or {@code pack.png}. */
    private static final int MAX_ROOT_FILE_BYTES = 1024 * 1024;

    /** What a pack says about itself: its description as plain text, and the pack format it was made for, or 0. */
    public record Meta(String description, int format) {
    }

    /** A kind of a component's content: the key holding it, and whether the game reads the value there. */
    private record Content(String key, Predicate<JsonElement> valid) {
        boolean heldBy(JsonObject component) {
            return this.valid.test(component.get(this.key));
        }
    }

    /**
     * The kinds of a component's content by the name its {@code type} gives: text, translation, key binding, score,
     * selector, NBT, and NeoForge's insertion of a translation's argument. Selectors and NBT paths are not parsed.
     */
    private static final Map<String, Content> COMPONENT_CONTENTS = Map.of(
            "text", new Content("text", PackFolders::isString),
            "translatable", new Content("translate", PackFolders::isString),
            "keybind", new Content("keybind", PackFolders::isString),
            "score", new Content("score", value -> value instanceof JsonObject score
                    && isString(score.get("name")) && isString(score.get("objective"))),
            "selector", new Content("selector", PackFolders::isString),
            "nbt", new Content("nbt", PackFolders::isString),
            "neoforge:inserting", new Content("index", value -> value instanceof JsonPrimitive index && index.isNumber()
                    && index.getAsNumber().intValue() >= 0));

    private PackFolders() {
    }

    /**
     * Whether the game takes {@code entry} as a pack: a folder or a file ending in {@code .zip}, as its
     * {@code PackDetector} finds them, with a {@code pack.mcmeta} at its root whose {@code pack} section has a
     * {@code pack_format} number and a {@code description}; the game leaves out a pack whose metadata it cannot read.
     */
    public static boolean isPack(Path entry) {
        if (!Files.isDirectory(entry) && !(Files.isRegularFile(entry) && entry.getFileName().toString().endsWith(".zip"))) {
            return false;
        }
        return section(entry).filter(section -> section.get("pack_format") instanceof JsonPrimitive format && format.isNumber()
                && isComponent(section.get("description"))).isPresent();
    }

    /**
     * Whether the game reads {@code value} as a text component (its {@code ComponentSerialization}): text, a list of at
     * least one component, or an object holding the content of the kind its {@code type} names, or without a type, of
     * any kind. Read against NeoForge 21.1's codec.
     */
    private static boolean isComponent(JsonElement value) {
        if (value instanceof JsonPrimitive) return isString(value);
        if (value instanceof JsonArray list) {
            if (list.isEmpty()) return false;
            for (JsonElement element : list) if (!isComponent(element)) return false;
            return true;
        }
        if (!(value instanceof JsonObject object)) return false;
        if (!object.has("type")) return COMPONENT_CONTENTS.values().stream().anyMatch(content -> content.heldBy(object));
        Content content = isString(object.get("type")) ? COMPONENT_CONTENTS.get(object.get("type").getAsString()) : null;
        return content != null && content.heldBy(object);
    }

    private static boolean isString(JsonElement value) {
        return value instanceof JsonPrimitive text && text.isString();
    }

    /** The {@code pack} section of a pack's {@code pack.mcmeta}, or empty without one it can read. */
    private static Optional<JsonObject> section(Path pack) {
        try {
            byte[] bytes = read(pack, "pack.mcmeta");
            if (bytes == null) return Optional.empty();
            JsonElement root = JsonParser.parseReader(new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8));
            return root.isJsonObject() && root.getAsJsonObject().get("pack") instanceof JsonObject section
                    ? Optional.of(section) : Optional.empty();
        } catch (IOException | RuntimeException unreadable) {
            return Optional.empty();
        }
    }

    /** The packs in {@code folder} by the id the game gives them, {@code file/} and their file name; none without it. */
    public static Map<String, Path> list(Path folder) throws IOException {
        Map<String, Path> packs = new TreeMap<>();
        if (!Files.isDirectory(folder)) return packs;
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder)) {
            for (Path entry : entries) {
                if (isPack(entry)) packs.put("file/" + entry.getFileName(), entry);
            }
        }
        return packs;
    }

    /** A pack's name as the game shows it: its folder or file name. */
    public static String title(Path pack) {
        return pack.getFileName().toString();
    }

    /** What the pack's {@code pack.mcmeta} says, or empty when it has none or it cannot be read. */
    public static Optional<Meta> meta(Path pack) {
        return section(pack).map(section -> new Meta(section.has("description") ? plain(section.get("description")) : "",
                section.get("pack_format") instanceof JsonPrimitive format && format.isNumber() ? format.getAsInt() : 0));
    }

    /** The bytes of {@code name} at the root of a folder or zip pack, at most a mebibyte, or null when it has none. */
    public static byte[] read(Path pack, String name) throws IOException {
        if (Files.isDirectory(pack)) {
            Path file = pack.resolve(name);
            return Files.isRegularFile(file) && Files.size(file) <= MAX_ROOT_FILE_BYTES ? Files.readAllBytes(file) : null;
        }
        try (ZipFile zip = new ZipFile(pack.toFile())) {
            var entry = zip.getEntry(name);
            if (entry == null || entry.isDirectory()) return null;
            try (InputStream input = zip.getInputStream(entry)) {
                return input.readNBytes(MAX_ROOT_FILE_BYTES);
            }
        }
    }

    /** A text component as plain text: its text, the key of a translation, and its siblings in order. */
    private static String plain(JsonElement component) {
        if (component.isJsonPrimitive()) return component.getAsString();
        StringBuilder text = new StringBuilder();
        if (component.isJsonArray()) {
            component.getAsJsonArray().forEach(part -> text.append(plain(part)));
        } else if (component.isJsonObject()) {
            var object = component.getAsJsonObject();
            if (object.has("text")) text.append(plain(object.get("text")));
            else if (object.has("translate")) text.append(plain(object.get("translate")));
            if (object.has("extra")) text.append(plain(object.get("extra")));
        }
        return text.toString();
    }

    /**
     * A pack folder as a sentence names it: a world's datapack with its world, such as {@code MyPack datapack of World},
     * otherwise a resource pack, such as {@code TotalDebug resource pack}.
     */
    public static String label(Path pack) {
        Path parent = pack.getParent();
        if (parent != null && parent.getFileName() != null && parent.getFileName().toString().equals("datapacks")
                && parent.getParent() != null) {
            return title(pack) + " datapack of " + parent.getParent().getFileName();
        }
        return title(pack) + " resource pack";
    }
}
