package com.github.minecraft_ta.totalDebugCompanion.decompile;

import com.github.minecraft_ta.totalDebugCompanion.source.SourceLineMap;
import com.github.minecraft_ta.totalDebugCompanion.source.SourceDocument;
import com.github.minecraft_ta.totalDebugCompanion.jdt.symbol.CodeSymbol;
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
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** One current runtime's readable source/debug pairs. The manifest commits each complete pair. */
final class DecompiledSourceStore {
    private static final int MAGIC = 0x54444442;
    private static final int FORMAT = 4;
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
            JsonObject classes = store.reusableClasses();
            if (classes == null) {
                classes = new JsonObject();
                // Invalidate old readers before replacing any files.
                store.writeManifest(classes);
            }
            store.removeUnlistedFiles(classes);
            return null;
        });
        return store;
    }

    /** Returns this runtime's listed classes, or null when the generated manifest must be replaced. */
    private JsonObject reusableClasses() {
        Path file = this.directory.resolve("manifest.json");
        if (!Files.isRegularFile(file)) return null;
        try {
            JsonObject manifest = JsonFiles.read(file);
            if (JsonFiles.integer(manifest, "format") != 1 || !this.identity.equals(JsonFiles.string(manifest, "id"))) {
                return null;
            }
            JsonObject classes = JsonFiles.object(manifest, "classes");
            for (var value : classes.asMap().values()) {
                CacheNames.requireFileName(value.getAsString());
            }
            return classes;
        } catch (IOException | IllegalArgumentException | IllegalStateException | UnsupportedOperationException
                 | ArithmeticException damaged) {
            // The manifest is generated data; an unreadable or unsupported one is rebuilt from current runtime sources.
            return null;
        }
    }

    Path directory() {
        return this.directory;
    }

    List<String> cachedClasses() throws IOException {
        return CacheFiles.locked(this.directory, () -> classes().keySet().stream().sorted().toList());
    }

    StoredSource read(String binaryName) throws IOException {
        return CacheFiles.locked(this.directory, () -> {
            JsonObject classes = classes();
            String stem = stem(classes, binaryName);
            if (stem == null) {
                return null;
            }
            SourceDocument document = readPair(classes, stem, binaryName);
            return document == null ? null : new StoredSource(this.directory.resolve(stem + ".java"), document);
        });
    }

    /** Reads a listed pair, or unlists and removes it when either generated file is missing or damaged. */
    private SourceDocument readPair(JsonObject classes, String stem, String binaryName) throws IOException {
        try {
            String source = Files.readString(this.directory.resolve(stem + ".java"), StandardCharsets.UTF_8);
            return readDebug(this.directory.resolve(stem + ".debug"), binaryName, source);
        } catch (IOException damaged) {
            classes.remove(binaryName);
            writeManifest(classes);
            removeUnlistedFiles(classes);
            return null;
        }
    }

    private static SourceDocument readDebug(Path file, String binaryName, String source) throws IOException {
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
            int symbolCount = readCount(input, 12);
            var symbols = new ArrayList<SourceDocument.SymbolSpan>(symbolCount);
            for (int i = 0; i < symbolCount; i++) {
                int kind = input.readUnsignedByte();
                String owner = input.readUTF();
                CodeSymbol symbol = switch (kind) {
                    case 0 -> new CodeSymbol.ClassSymbol(owner);
                    case 1 -> new CodeSymbol.FieldSymbol(owner, input.readUTF(), input.readUTF());
                    case 2 -> new CodeSymbol.MethodSymbol(owner, input.readUTF(), input.readUTF());
                    default -> throw new IOException("Invalid source symbol kind: " + kind);
                };
                int role = input.readUnsignedByte();
                if (role >= SourceDocument.SymbolRole.values().length) throw new IOException("Invalid source symbol role: " + role);
                symbols.add(new SourceDocument.SymbolSpan(symbol, SourceDocument.SymbolRole.values()[role], input.readInt(), input.readInt()));
            }
            if (input.read() != -1) {
                throw new IOException("Trailing data in decompiled debug metadata: " + file);
            }
            return new SourceDocument(binaryName, source, SourceLineMap.fromOriginalToDisplayed(mapping), SourceVariableNames.of(methods), symbols);
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid decompiled debug metadata: " + file, exception);
        }
    }

    Path write(SourceDocument document) throws IOException {
        String binaryName = document.binaryName();
        String source = document.contents();
        SourceLineMap lines = document.lineMap();
        SourceVariableNames names = document.variableNames();
        if (Objects.requireNonNull(binaryName).isBlank() || binaryName.contains("/") || binaryName.contains("\\")) {
            throw new IllegalArgumentException("Expected a Java binary name: " + binaryName);
        }
        return CacheFiles.locked(this.directory, () -> {
            JsonObject classes = classes();
            String existing = stem(classes, binaryName);
            if (existing != null && readPair(classes, existing, binaryName) != null) {
                return this.directory.resolve(existing + ".java");
            }
            var used = new HashSet<String>();
            classes.asMap().values().forEach(value -> used.add(value.getAsString().toLowerCase(Locale.ROOT)));
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
                    output.writeInt(document.symbols().size());
                    for (var span : document.symbols()) {
                        switch (span.symbol()) {
                            case CodeSymbol.ClassSymbol symbol -> { output.writeByte(0); output.writeUTF(symbol.className()); }
                            case CodeSymbol.FieldSymbol symbol -> { output.writeByte(1); output.writeUTF(symbol.ownerClassName()); output.writeUTF(symbol.name()); output.writeUTF(symbol.descriptor()); }
                            case CodeSymbol.MethodSymbol symbol -> { output.writeByte(2); output.writeUTF(symbol.ownerClassName()); output.writeUTF(symbol.name()); output.writeUTF(symbol.descriptor()); }
                        }
                        output.writeByte(span.role().ordinal()); output.writeInt(span.offset()); output.writeInt(span.length());
                    }
                }
            });
            classes.addProperty(binaryName, stem);
            writeManifest(classes);
            return file;
        });
    }

    private void writeManifest(JsonObject classes) throws IOException {
        JsonObject manifest = new JsonObject();
        manifest.addProperty("format", 1);
        manifest.addProperty("id", this.identity);
        manifest.add("classes", classes);
        JsonFiles.write(this.directory.resolve("manifest.json"), manifest);
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
        var retained = new HashSet<String>();
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
            var generated = new ArrayList<Path>();
            for (Path entry : entries.toList()) {
                String name = entry.getFileName().toString();
                if (name.equals("manifest.json") || name.equals(".lock")) {
                    continue;
                }
                if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)
                        || !(name.endsWith(".java") || name.endsWith(".debug"))) {
                    throw new IOException("Unsupported decompiled cache entry: " + entry
                            + ". Clear this generated cache manually before using the current layout.");
                }
                generated.add(entry);
            }
            return generated;
        }
    }

    record StoredSource(Path path, SourceDocument document) { }

    private static String fingerprint(String... values) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
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

}
