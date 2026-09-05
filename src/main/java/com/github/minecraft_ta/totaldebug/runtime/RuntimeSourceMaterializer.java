package com.github.minecraft_ta.totaldebug.runtime;

import com.github.minecraft_ta.totaldebug.storage.AtomicFiles;
import com.github.minecraft_ta.totaldebug.storage.CacheFiles;
import com.github.minecraft_ta.totaldebug.storage.CacheNames;
import com.github.minecraft_ta.totaldebug.storage.JsonFiles;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.github.minecraft_ta.totaldebug.runtime.RuntimeSourceContents.*;

/** Materializes only loader views that cannot be represented by their original archives. */
public final class RuntimeSourceMaterializer {
    private static final int FORMAT = 2;
    private static final String MANIFEST = "manifest.json";

    private record Input(RuntimeSourceInventory.Source original, Path readable, String fingerprint, String file) { }

    private RuntimeSourceMaterializer() { }

    public static PreparedRuntimeSources prepare(List<RuntimeSourceInventory.Source> sources, Path cacheDirectory)
            throws IOException {
        var inputs = List.copyOf(sources);
        if (inputs.isEmpty()) {
            throw new IOException("The runtime has no class sources");
        }
        Path cache = Objects.requireNonNull(cacheDirectory, "cacheDirectory").toAbsolutePath().normalize();
        if (isVirtual(cache)) {
            throw new IOException("Runtime source cache must be on the default filesystem: " + cache);
        }
        return CacheFiles.locked(cache.getParent(), () -> prepareLocked(inputs, cache));
    }

    private static PreparedRuntimeSources prepareLocked(List<RuntimeSourceInventory.Source> sources, Path cache)
            throws IOException {
        List<Input> inputs;
        try (var phase = com.github.minecraft_ta.totaldebug.storage.RuntimePhase.start("runtime.source-identities")) {
            inputs = inspect(sources);
        }
        String id = fingerprint(inputs, cache);
        Files.createDirectories(cache);
        AtomicFiles.cleanupAbandonedStaging(cache.getParent());
        AtomicFiles.cleanupAbandonedStaging(cache);
        requireCurrentLayout(cache);
        Map<String, JsonObject> previous = readEntries(cache.resolve(MANIFEST));
        JsonObject manifest = new JsonObject();
        manifest.addProperty("format", FORMAT);
        manifest.addProperty("id", id);
        JsonArray files = new JsonArray();
        manifest.add("files", files);
        var replacements = new ArrayList<String>();
        var names = new HashSet<String>();
        Path staged = null;
        try {
            for (Input input : inputs) {
                checkInterrupted();
                if (input.file() == null) {
                    continue;
                }
                names.add(input.file());
                JsonObject entry = previous.get(input.file());
                if (!reusable(cache, input, entry)) {
                    if (staged == null) {
                        staged = Files.createTempDirectory(cache.getParent(), ".td-" + ProcessHandle.current().pid() + "-");
                    }
                    Path destination = staged.resolve(input.file());
                    if (Files.isDirectory(input.readable())) {
                        try (var phase = com.github.minecraft_ta.totaldebug.storage.RuntimePhase.start("runtime.pack-source")) {
                            packClassDirectory(input.readable(), destination);
                        }
                        if (!input.fingerprint().equals(archiveFingerprint(destination))) {
                            throw new IOException("Runtime class source changed during preparation: " + input.readable());
                        }
                    } else {
                        try (var stream = Files.newInputStream(input.readable())) {
                            Files.copy(stream, destination);
                        }
                        if (!input.fingerprint().equals(fileFingerprint(destination))) {
                            throw new IOException("Runtime archive changed during preparation: " + input.readable());
                        }
                    }
                    entry = new JsonObject();
                    entry.addProperty("file", input.file());
                    entry.addProperty("fingerprint", input.fingerprint());
                    entry.addProperty("size", Files.size(destination));
                    entry.addProperty("sha256", fileFingerprint(destination));
                    replacements.add(input.file());
                }
                files.add(entry);
            }
            if (!replacements.isEmpty() || !matchesManifest(cache.resolve(MANIFEST), manifest)) {
                checkInterrupted();
                // Readers share this lock. Invalidate identity before changing any published bytes.
                Files.deleteIfExists(cache.getParent().resolve("inventory.json"));
                Files.deleteIfExists(cache.resolve(MANIFEST));
                for (String name : replacements) {
                    Files.move(staged.resolve(name), cache.resolve(name),
                            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                }
                try (var entries = Files.list(cache)) {
                    for (Path entry : entries.toList()) {
                        if (entry.getFileName().toString().endsWith(".jar") && !names.contains(entry.getFileName().toString())) {
                            Files.delete(entry);
                        }
                    }
                }
                JsonFiles.write(cache.resolve(MANIFEST), manifest);
            }
        } finally {
            if (staged != null) {
                AtomicFiles.deleteOwned(cache.getParent(), staged);
            }
        }
        return new PreparedRuntimeSources(id, cache, inputs.stream().map(input ->
                new PreparedRuntimeSources.Source(input.original(), input.file() == null
                        ? input.readable() : cache.resolve(input.file()))).toList());
    }

    private static List<Input> inspect(List<RuntimeSourceInventory.Source> sources) throws IOException {
        var inputs = new ArrayList<Input>();
        var representatives = new HashSet<Path>();
        var names = new HashSet<String>();
        for (var source : sources) {
            checkInterrupted();
            Path path = source.path();
            if (!Files.exists(path)) {
                throw new IOException("Runtime class source does not exist: " + path.toUri());
            }
            if (representatives.contains(path)) {
                continue;
            }
            boolean directory = Files.isDirectory(path);
            String fingerprint = directory ? directoryFingerprint(path) : fileFingerprint(path);
            Path readable = path;
            if (directory && isVirtual(path)) {
                Path equivalent = equivalentArchive(path, fingerprint);
                if (equivalent != null) {
                    readable = equivalent;
                    // Copied archives are verified byte for byte; physical ones use the proven class view.
                    if (isVirtual(readable)) {
                        fingerprint = fileFingerprint(readable);
                    }
                }
            }
            if (!representatives.add(readable)) {
                continue;
            }
            String file = null;
            if (isVirtual(readable)) {
                String label = readable.getFileName() == null ? "" : readable.getFileName().toString();
                label = label.endsWith(".jar") ? label.substring(0, label.length() - 4)
                        : Objects.requireNonNullElse(source.moduleName(), "runtime");
                file = CacheNames.uniqueStem(label, names) + ".jar";
            }
            inputs.add(new Input(source, readable, fingerprint, file));
        }
        return inputs;
    }

    private static String fingerprint(List<Input> inputs, Path cache) {
        var digest = digest();
        update(digest, Integer.toString(FORMAT));
        for (Input input : inputs) {
            update(digest, (input.file() == null ? input.readable() : cache.resolve(input.file())).toUri().toASCIIString());
            update(digest, Objects.requireNonNullElse(input.original().moduleName(), ""));
            update(digest, input.fingerprint());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static Map<String, JsonObject> readEntries(Path manifestFile) {
        try {
            JsonObject manifest = JsonFiles.read(manifestFile);
            if (JsonFiles.integer(manifest, "format") != FORMAT) {
                return Map.of();
            }
            var entries = new HashMap<String, JsonObject>();
            for (var value : JsonFiles.array(manifest, "files")) {
                JsonObject entry = value.getAsJsonObject();
                if (entries.putIfAbsent(JsonFiles.string(entry, "file"), entry) != null) {
                    return Map.of();
                }
            }
            return entries;
        } catch (IOException | RuntimeException ignored) {
            return Map.of();
        }
    }

    private static boolean reusable(Path cache, Input input, JsonObject entry) {
        if (entry == null) {
            return false;
        }
        Path file = cache.resolve(input.file());
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || !input.fingerprint().equals(JsonFiles.string(entry, "fingerprint"))
                    || Files.size(file) != entry.get("size").getAsBigDecimal().longValueExact()) {
                return false;
            }
            String expectedHash = JsonFiles.string(entry, "sha256");
            return expectedHash.equals(fileFingerprint(file));
        } catch (java.util.concurrent.CancellationException exception) {
            throw exception;
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    private static boolean matchesManifest(Path file, JsonObject expected) {
        try {
            return expected.equals(JsonFiles.read(file));
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    private static void requireCurrentLayout(Path cache) throws IOException {
        try (var entries = Files.list(cache)) {
            for (Path entry : entries.toList()) {
                String name = entry.getFileName().toString();
                if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)
                        || !(name.equals(MANIFEST) || name.endsWith(".jar"))) {
                    throw new IOException("Unsupported runtime source cache entry: " + entry
                            + ". Clear this generated cache manually before using the current layout.");
                }
            }
        }
    }

    private static void packClassDirectory(Path source, Path target) throws IOException {
        try (var output = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(target), 65536))) {
            for (Path file : classFiles(source)) {
                checkInterrupted();
                ZipEntry entry = new ZipEntry(entryName(source, file));
                entry.setTime(Files.getLastModifiedTime(file).toMillis());
                output.putNextEntry(entry);
                Files.copy(file, output);
                output.closeEntry();
            }
        }
    }

    private static boolean isVirtual(Path path) {
        return path.getFileSystem() != FileSystems.getDefault();
    }
}
