package com.github.minecraft_ta.totaldebug.storage;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** The game-owned runtime description shared by compiler/index consumers. */
public record RuntimeInventory(
        String id,
        String javaRuntimeVersion,
        String javaHome,
        boolean production,
        List<Source> sources
) {
    public static final int FORMAT_VERSION = 1;

    public enum SourceKind { ARCHIVE, DIRECTORY }
    public enum ModuleKind { PLATFORM, MOD, LIBRARY, JAVA_RUNTIME }

    public record RuntimeModule(String id, String displayName, ModuleKind kind) {
        public RuntimeModule {
            requireText(id, "module id");
            requireText(displayName, "module display name");
            Objects.requireNonNull(kind, "module kind");
        }

        public JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("id", id);
            json.addProperty("name", displayName);
            json.addProperty("kind", kind.name());
            return json;
        }

        public static RuntimeModule fromJson(JsonObject json) {
            return new RuntimeModule(JsonFiles.string(json, "id"), JsonFiles.string(json, "name"),
                    ModuleKind.valueOf(JsonFiles.string(json, "kind")));
        }
    }

    public record Source(SourceKind kind, Path path, String logicalUri, RuntimeModule module) {
        public Source {
            Objects.requireNonNull(kind, "kind");
            path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
            if (path.getFileSystem() != FileSystems.getDefault()) {
                throw new IllegalArgumentException("Published source is not a physical filesystem path: " + path);
            }
            requireText(logicalUri, "logical URI");
            Objects.requireNonNull(module, "module");
        }
    }

    public RuntimeInventory {
        requireText(id, "inventory id");
        requireText(javaRuntimeVersion, "Java runtime version");
        requireText(javaHome, "Java home");
        sources = List.copyOf(sources);
        if (sources.isEmpty()) {
            throw new IllegalArgumentException("Runtime inventory has no sources");
        }
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("format", FORMAT_VERSION);
        json.addProperty("id", id);
        json.addProperty("javaVersion", javaRuntimeVersion);
        json.addProperty("javaHome", javaHome);
        json.addProperty("production", production);
        JsonArray entries = new JsonArray();
        for (Source source : sources) {
            JsonObject entry = new JsonObject();
            entry.addProperty("kind", source.kind().name());
            entry.addProperty("path", source.path().toUri().toASCIIString());
            entry.addProperty("logicalUri", source.logicalUri());
            entry.add("module", source.module().toJson());
            entries.add(entry);
        }
        json.add("sources", entries);
        return json;
    }

    public void write(Path path) throws IOException {
        AtomicFiles.replace(path, staged -> {
            Files.writeString(staged, JsonFiles.GSON.toJson(toJson()), java.nio.charset.StandardCharsets.UTF_8);
            read(staged);
        });
    }

    public static RuntimeInventory read(Path file) throws IOException {
        try {
            JsonObject json = JsonFiles.read(file);
            int format = JsonFiles.integer(json, "format");
            if (format != FORMAT_VERSION) {
                throw new IllegalArgumentException("Unsupported runtime inventory format " + format
                        + "; required " + FORMAT_VERSION + ". Use matching TotalDebug and Companion builds.");
            }
            List<Source> sources = new ArrayList<>();
            for (var value : JsonFiles.array(json, "sources")) {
                JsonObject source = value.getAsJsonObject();
                SourceKind kind = SourceKind.valueOf(JsonFiles.string(source, "kind"));
                URI uri = URI.create(JsonFiles.string(source, "path"));
                if (!"file".equalsIgnoreCase(uri.getScheme())) {
                    throw new IllegalArgumentException("Runtime source is not a file URI: " + uri);
                }
                Path path = Path.of(uri);
                if (kind == SourceKind.ARCHIVE ? !Files.isRegularFile(path) : !Files.isDirectory(path)) {
                    throw new IllegalArgumentException("Runtime source is unavailable: " + path);
                }
                sources.add(new Source(kind, path, JsonFiles.string(source, "logicalUri"),
                        RuntimeModule.fromJson(JsonFiles.object(source, "module"))));
            }
            return new RuntimeInventory(JsonFiles.string(json, "id"), JsonFiles.string(json, "javaVersion"),
                    JsonFiles.string(json, "javaHome"), JsonFiles.bool(json, "production"), sources);
        } catch (RuntimeException exception) {
            throw new IOException("Invalid runtime inventory " + file + ": " + exception.getMessage(), exception);
        }
    }

    private static void requireText(String value, String name) {
        if (Objects.requireNonNull(value, name).isBlank()) {
            throw new IllegalArgumentException("Blank " + name);
        }
    }
}
