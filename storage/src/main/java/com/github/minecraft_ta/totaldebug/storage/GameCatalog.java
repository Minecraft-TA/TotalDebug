package com.github.minecraft_ta.totaldebug.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipFile;

/** One offline capture of registries and client resources. Entries describe default stacks/states. */
public record GameCatalog(int format, String inventoryId, String capturedAt, String language,
                          List<Entry> entries, Map<String, List<String>> resources, List<String> warnings) {
    public static final String MANIFEST = "catalog.json";
    public static final int CURRENT_FORMAT = 2;
    public static final Gson GSON = new GsonBuilder().create();

    public enum Kind { ITEM, BLOCK }

    public record Entry(Kind kind, String id, String name, String modName, String modVersion,
                        String className, String counterpart, String model, Map<String, String> properties,
                        Map<Integer, Integer> tintColors) {
        public Entry {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(id);
            if (!id.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) throw new IllegalArgumentException("Invalid registry ID: " + id);
            Objects.requireNonNull(name);
            Objects.requireNonNull(modName);
            Objects.requireNonNull(modVersion);
            Objects.requireNonNull(className);
            Objects.requireNonNull(counterpart);
            Objects.requireNonNull(model);
            properties = Map.copyOf(properties);
            tintColors = Map.copyOf(tintColors);
            if (tintColors.keySet().stream().anyMatch(index -> index < 0))
                throw new IllegalArgumentException("Captured tint indices must be non-negative");
        }

        public String namespace() { return id.substring(0, id.indexOf(':')); }

        @Override public String toString() { return name + "  ·  " + id; }
    }

    public GameCatalog {
        validateFormat(format);
        Objects.requireNonNull(inventoryId);
        Objects.requireNonNull(capturedAt);
        Objects.requireNonNull(language);
        entries = List.copyOf(entries);
        var copied = new java.util.TreeMap<String, List<String>>();
        resources.forEach((path, providers) -> {
            validateResourcePath(path);
            if (providers.isEmpty()) throw new IllegalArgumentException("Resource has no provider: " + path);
            copied.put(path, List.copyOf(providers));
        });
        resources = java.util.Collections.unmodifiableMap(copied);
        warnings = List.copyOf(warnings);
    }

    public static void validateResourcePath(String path) {
        if (!path.startsWith("assets/") || path.contains("\\") || path.contains(":"))
            throw new IllegalArgumentException("Invalid asset path: " + path);
        for (String segment : path.split("/", -1))
            if (segment.isEmpty() || segment.equals(".") || segment.equals(".."))
                throw new IllegalArgumentException("Invalid asset path: " + path);
    }

    public String effectiveEntry(String resourcePath) {
        validateResourcePath(resourcePath);
        List<String> providers = resources.get(resourcePath);
        if (providers == null) throw new IllegalArgumentException("Resource was not captured: " + resourcePath);
        return "layers/" + (providers.size() - 1) + "/" + resourcePath;
    }

    public static GameCatalog read(Path archive) throws IOException {
        try (var zip = new ZipFile(archive.toFile())) {
            var entry = zip.getEntry(MANIFEST);
            if (entry == null || entry.getSize() > 64 * 1024 * 1024)
                throw new IOException("Missing or oversized game catalog");
            try (var reader = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
                var manifest = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
                validateFormat(manifest.get("format").getAsInt());
                return Objects.requireNonNull(GSON.fromJson(manifest, GameCatalog.class));
            }
        } catch (RuntimeException failure) {
            throw new IOException("Invalid game catalog: " + failure.getMessage(), failure);
        }
    }

    private static void validateFormat(int format) {
        if (format != CURRENT_FORMAT)
            throw new IllegalArgumentException("Unsupported game catalog format: " + format
                    + "; expected " + CURRENT_FORMAT + " with captured item tints. Update both applications, then use Browse > Refresh game data.");
    }
}
