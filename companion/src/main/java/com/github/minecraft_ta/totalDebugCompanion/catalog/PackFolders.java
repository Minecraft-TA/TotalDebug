package com.github.minecraft_ta.totalDebugCompanion.catalog;

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
                && section.has("description")).isPresent();
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
