package com.github.minecraft_ta.totalDebugCompanion.decompile;

import com.github.minecraft_ta.totalDebugCompanion.source.SourceLineMap;
import com.github.minecraft_ta.totalDebugCompanion.source.SourceVariableNames;
import com.github.minecraft_ta.totaldebug.storage.AtomicFiles;
import com.github.minecraft_ta.totaldebug.storage.InstancePaths;
import com.github.minecraft_ta.totaldebug.storage.CacheFiles;
import com.github.minecraft_ta.totaldebug.storage.CacheNames;
import com.github.minecraft_ta.totaldebug.storage.JsonFiles;
import com.google.gson.JsonObject;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** One current runtime's readable source/debug pairs. The manifest commits each complete pair. */
final class DecompiledSourceStore {
    private static final int MAGIC = 0x54444442;
    private static final int FORMAT = 2;
    private final Path directory;
    private final String identity;

    private DecompiledSourceStore(Path directory, String identity) {
        this.directory = directory;
        this.identity = identity;
    }

    static DecompiledSourceStore open(Path instanceHome, String runtimeSignature, String decompilerFormat) throws IOException {
        if (Objects.requireNonNull(runtimeSignature).isBlank() || Objects.requireNonNull(decompilerFormat).isBlank()) {
            throw new IllegalArgumentException("Runtime signature and decompiler format must not be blank");
        }
        var store = new DecompiledSourceStore(new InstancePaths(instanceHome).decompiled(),
                fingerprint(runtimeSignature, decompilerFormat));
        CacheFiles.locked(store.directory, () -> {
            AtomicFiles.cleanupAbandonedStaging(store.directory);
            store.generatedFiles();
            Path file = store.directory.resolve("manifest.json");
            JsonObject manifest = Files.isRegularFile(file) ? JsonFiles.read(file) : null;
            if (manifest != null && JsonFiles.integer(manifest, "format") != 1) {
                throw new IOException("Unsupported decompiled cache format: " + file);
            }
            if (manifest == null || !store.identity.equals(JsonFiles.string(manifest, "id"))) {
                manifest = new JsonObject();
                manifest.addProperty("format", 1);
                manifest.addProperty("id", store.identity);
                manifest.add("classes", new JsonObject());
                // Invalidate old readers before replacing any files.
                JsonFiles.write(file, manifest);
            }
            store.removeUnlistedFiles(JsonFiles.object(manifest, "classes"));
            return null;
        });
        return store;
    }

    Path directory() {
        return this.directory;
    }

    List<String> cachedClasses() throws IOException {
        return CacheFiles.locked(this.directory, () -> classes().keySet().stream().sorted().toList());
    }

    StoredSource read(String binaryName) throws IOException {
        return CacheFiles.locked(this.directory, () -> {
            String stem = stem(classes(), binaryName);
            if (stem == null) {
                return null;
            }
            Path file = this.directory.resolve(stem + ".java");
            String source = Files.readString(file, StandardCharsets.UTF_8);
            return new StoredSource(file, source, readDebug(this.directory.resolve(stem + ".debug"), binaryName, source));
        });
    }

    private static DebugMetadata readDebug(Path file, String binaryName, String source) throws IOException {
        try (DataInputStream input = new DataInputStream(Files.newInputStream(file))) {
            if (!readHeader(input).equals(binaryName) || !input.readUTF().equals(fingerprint(source))) {
                throw new IOException("Decompiled source/debug pair does not match: " + file);
            }
            int length = readCount(input, Integer.BYTES);
            if (length % 2 != 0) {
                throw new IOException("Invalid line map length: " + length);
            }
            int[] mapping = new int[length];
            for (int i = 0; i < length; i++) {
                mapping[i] = input.readInt();
            }
            int methodCount = readCount(input, 8);
            var methods = new LinkedHashMap<SourceVariableNames.MethodKey, Map<String, String>>();
            for (int m = 0; m < methodCount; m++) {
                var method = new SourceVariableNames.MethodKey(input.readUTF(), input.readUTF());
                int variableCount = readCount(input, 4);
                Map<String, String> variables = new LinkedHashMap<>();
                for (int v = 0; v < variableCount; v++) {
                    String name = input.readUTF();
                    if (variables.put(name, input.readUTF()) != null) {
                        throw new IOException("Duplicate runtime variable name: " + name);
                    }
                }
                if (methods.put(method, Map.copyOf(variables)) != null) {
                    throw new IOException("Duplicate method variable names: " + method);
                }
            }
            if (input.read() != -1) {
                throw new IOException("Trailing data in decompiled debug metadata: " + file);
            }
            return new DebugMetadata(SourceLineMap.fromOriginalToDisplayed(mapping), SourceVariableNames.of(methods));
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid decompiled debug metadata: " + file, exception);
        }
    }

    Path write(String binaryName, String source, SourceLineMap lines, SourceVariableNames names) throws IOException {
        Objects.requireNonNull(source);
        Objects.requireNonNull(lines);
        Objects.requireNonNull(names);
        if (Objects.requireNonNull(binaryName).isBlank() || binaryName.contains("/") || binaryName.contains("\\")) {
            throw new IllegalArgumentException("Expected a Java binary name: " + binaryName);
        }
        return CacheFiles.locked(this.directory, () -> {
            JsonObject classes = classes();
            String existing = stem(classes, binaryName);
            if (existing != null) {
                Path file = this.directory.resolve(existing + ".java");
                readDebug(this.directory.resolve(existing + ".debug"), binaryName, Files.readString(file));
                return file;
            }
            var used = new java.util.HashSet<String>();
            classes.asMap().values().forEach(value -> used.add(value.getAsString().toLowerCase(java.util.Locale.ROOT)));
            String stem = CacheNames.uniqueStem(binaryName, used);
            Path file = this.directory.resolve(stem + ".java");
            AtomicFiles.writeString(file, source);
            AtomicFiles.replace(this.directory.resolve(stem + ".debug"), staged -> {
                try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(staged))) {
                    output.writeInt(MAGIC);
                    output.writeInt(FORMAT);
                    output.writeUTF(binaryName);
                    output.writeUTF(fingerprint(source));
                    int[] mapping = lines.originalToDisplayed();
                    output.writeInt(mapping.length);
                    for (int value : mapping) {
                        output.writeInt(value);
                    }
                    output.writeInt(names.mappings().size());
                    for (var method : names.mappings().entrySet()) {
                        output.writeUTF(method.getKey().name());
                        output.writeUTF(method.getKey().descriptor());
                        output.writeInt(method.getValue().size());
                        for (var variable : method.getValue().entrySet()) {
                            output.writeUTF(variable.getKey());
                            output.writeUTF(variable.getValue());
                        }
                    }
                }
            });
            classes.addProperty(binaryName, stem);
            JsonObject manifest = new JsonObject();
            manifest.addProperty("format", 1);
            manifest.addProperty("id", this.identity);
            manifest.add("classes", classes);
            JsonFiles.write(this.directory.resolve("manifest.json"), manifest);
            return file;
        });
    }

    private JsonObject classes() throws IOException {
        Path file = this.directory.resolve("manifest.json");
        JsonObject manifest = JsonFiles.read(file);
        if (!this.identity.equals(JsonFiles.string(manifest, "id"))) {
            throw new IOException("Decompiled cache belongs to a different runtime: " + file);
        }
        if (JsonFiles.integer(manifest, "format") != 1) {
            throw new IOException("Unsupported decompiled cache format: " + file);
        }
        JsonObject classes = JsonFiles.object(manifest, "classes");
        for (var value : classes.asMap().values()) {
            CacheNames.requireFileName(value.getAsString());
        }
        return classes;
    }

    private static String stem(JsonObject classes, String binaryName) {
        var value = classes.get(binaryName);
        return value == null ? null : CacheNames.requireFileName(value.getAsString());
    }

    private void removeUnlistedFiles(JsonObject classes) throws IOException {
        var retained = new java.util.HashSet<String>();
        for (var value : classes.asMap().values()) {
            String stem = CacheNames.requireFileName(value.getAsString());
            retained.add(stem + ".java");
            retained.add(stem + ".debug");
        }
        for (Path entry : generatedFiles()) {
            if (!retained.contains(entry.getFileName().toString())) {
                Files.delete(entry);
            }
        }
    }

    private List<Path> generatedFiles() throws IOException {
        try (var entries = Files.list(this.directory)) {
            var generated = new java.util.ArrayList<Path>();
            for (Path entry : entries.toList()) {
                String name = entry.getFileName().toString();
                if (name.equals("manifest.json") || name.equals(".lock")) {
                    continue;
                }
                if (!Files.isRegularFile(entry, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                        || !(name.endsWith(".java") || name.endsWith(".debug"))) {
                    throw new IOException("Unsupported decompiled cache entry: " + entry
                            + ". Clear this generated cache manually before using the current layout.");
                }
                generated.add(entry);
            }
            return generated;
        }
    }

    record StoredSource(Path path, String source, DebugMetadata debug) { }

    private static String fingerprint(String... values) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String readHeader(DataInputStream input) throws IOException {
        if (input.readInt() != MAGIC || input.readInt() != FORMAT) {
            throw new IOException("Unsupported decompiled debug metadata");
        }
        return input.readUTF();
    }

    private static int readCount(DataInputStream input, int minimumBytesPerEntry) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > input.available() / minimumBytesPerEntry) {
            throw new IOException("Invalid debug metadata entry count: " + count);
        }
        return count;
    }

    record DebugMetadata(SourceLineMap lines, SourceVariableNames names) { }
}
