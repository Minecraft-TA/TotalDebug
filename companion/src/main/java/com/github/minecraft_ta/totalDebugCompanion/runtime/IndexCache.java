package com.github.minecraft_ta.totalDebugCompanion.runtime;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.RuntimeSnapshotBytecodeSource.Source;
import com.github.minecraft_ta.totaldebug.storage.AtomicFiles;
import com.github.minecraft_ta.totaldebug.storage.JsonFiles;
import com.github.minecraft_ta.totaldebug.storage.RuntimeInventory.RuntimeModule;
import com.github.tth05.jindex.ClassIndex;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipFile;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** The native index entry and its source identities are committed as one replaceable archive. */
final class IndexCache {
    static final int FORMAT = 1;
    private static final String MANIFEST = "manifest.json";

    record Manifest(String inventoryId, List<Source> sources) {
        Manifest {
            if (inventoryId == null || inventoryId.isBlank()) {
                throw new IllegalArgumentException("The index must have an inventory identity");
            }
            sources = List.copyOf(sources);
            if (sources.isEmpty()) {
                throw new IllegalArgumentException("The index must have source metadata");
            }
        }
    }

    private IndexCache() {
    }

    /** Returns the validated, independently owned index after the file is published. */
    static ClassIndex write(Path target, ClassIndex index, Manifest manifest) throws IOException {
        return write(target, index, manifest, () -> { });
    }

    static ClassIndex write(Path target, ClassIndex index, Manifest manifest, Runnable checkpoint) throws IOException {
        ClassIndex[] verified = new ClassIndex[1];
        boolean published = false;
        try (var phase = com.github.minecraft_ta.totaldebug.storage.RuntimePhase.start("index.cache-publish")) {
            AtomicFiles.replace(target, staged -> {
                checkpoint.run();
                // JIndex writes a ZIP with a Zstd entry. Copy it raw, preserving its
                // compression and position: the native reader expects index at entry zero.
                Path nativeFile = AtomicFiles.temporaryFile(staged.getParent());
                try {
                    try (var save = com.github.minecraft_ta.totaldebug.storage.RuntimePhase.start("index.native-save")) {
                        index.saveToFile(nativeFile.toString());
                    }
                    checkpoint.run();
                    try (ZipFile input = ZipFile.builder().setPath(nativeFile).get();
                         ZipArchiveOutputStream output = new ZipArchiveOutputStream(staged)) {
                        var entries = input.getEntries();
                        if (!entries.hasMoreElements()) {
                            throw new IOException("JIndex produced an empty archive");
                        }
                        ZipArchiveEntry entry = entries.nextElement();
                        if (!entry.getName().equals("index") || entries.hasMoreElements()) {
                            throw new IOException("JIndex produced an unexpected archive layout");
                        }
                        try (var raw = input.getRawInputStream(entry)) {
                            output.addRawArchiveEntry(new ZipArchiveEntry(entry), raw);
                        }
                        output.putArchiveEntry(new ZipArchiveEntry(MANIFEST));
                        output.write(JsonFiles.GSON.toJson(toJson(manifest)).getBytes(StandardCharsets.UTF_8));
                        output.closeArchiveEntry();
                    }
                    requireSources(read(staged));
                    checkpoint.run();
                    // Native loading consumes the archive and releases its file handle. The
                    // validated object remains usable after the staged file is renamed.
                    try (var validation = com.github.minecraft_ta.totaldebug.storage.RuntimePhase.start("index.cache-validation")) {
                        verified[0] = ClassIndex.fromFile(staged.toString());
                    }
                    checkpoint.run();
                } finally {
                    Files.deleteIfExists(nativeFile);
                }
            });
            published = true;
            return verified[0];
        } finally {
            if (!published && verified[0] != null) {
                verified[0].close();
            }
        }
    }

    static Manifest read(Path file) throws IOException {
        try (ZipFile archive = ZipFile.builder().setPath(file).get()) {
            var entries = archive.getEntries();
            if (!entries.hasMoreElements() || !entries.nextElement().getName().equals("index")
                    || !entries.hasMoreElements() || !entries.nextElement().getName().equals(MANIFEST)
                    || entries.hasMoreElements()) {
                throw new IOException("Expected index and manifest.json entries in " + file);
            }
            JsonObject json;
            try (var reader = new InputStreamReader(archive.getInputStream(archive.getEntry(MANIFEST)), StandardCharsets.UTF_8)) {
                json = JsonParser.parseReader(reader).getAsJsonObject();
            }
            if (JsonFiles.integer(json, "format") != FORMAT) {
                throw new IOException("Unsupported runtime index format: " + file);
            }
            List<Source> sources = new ArrayList<>();
            var ids = new HashSet<Integer>();
            for (var value : JsonFiles.array(json, "sources")) {
                JsonObject source = value.getAsJsonObject();
                int id = JsonFiles.integer(source, "id");
                if (id < 0 || !ids.add(id)) {
                    throw new IOException("Invalid or duplicate runtime source id: " + id);
                }
                Path path = Path.of(URI.create(JsonFiles.string(source, "path")));
                sources.add(new Source(id, path, JsonFiles.string(source, "logicalUri"),
                        RuntimeModule.fromJson(JsonFiles.object(source, "module"))));
            }
            return new Manifest(JsonFiles.string(json, "inventoryId"), sources);
        } catch (RuntimeException exception) {
            throw new IOException("Invalid runtime index " + file + ": " + exception.getMessage(), exception);
        }
    }

    static void requireSources(Manifest manifest) throws IOException {
        for (Source source : manifest.sources()) {
            Path path = source.path();
            if (!Files.isRegularFile(path) && !Files.isDirectory(path)) {
                throw new IOException("Prepared runtime source is unavailable: " + path);
            }
        }
    }

    private static JsonObject toJson(Manifest manifest) {
        JsonObject json = new JsonObject();
        json.addProperty("format", FORMAT);
        json.addProperty("inventoryId", manifest.inventoryId());
        JsonArray sources = new JsonArray();
        for (Source source : manifest.sources()) {
            JsonObject value = new JsonObject();
            value.addProperty("id", source.sourceId());
            value.addProperty("path", source.path().toUri().toASCIIString());
            value.addProperty("logicalUri", source.logicalUri());
            value.add("module", source.module().toJson());
            sources.add(value);
        }
        json.add("sources", sources);
        return json;
    }
}
