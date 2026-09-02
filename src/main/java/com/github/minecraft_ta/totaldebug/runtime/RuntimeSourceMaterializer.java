package com.github.minecraft_ta.totaldebug.runtime;

import com.github.minecraft_ta.totaldebug.storage.AtomicFiles;
import com.github.minecraft_ta.totaldebug.storage.CacheFiles;
import com.github.minecraft_ta.totaldebug.storage.CacheNames;
import com.github.minecraft_ta.totaldebug.storage.JsonFiles;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Materializes virtual class roots without teaching consumers about loader-specific filesystems. */
public final class RuntimeSourceMaterializer {
    private static final String FORMAT = "2";
    private static final String MANIFEST = "manifest.json";

    private RuntimeSourceMaterializer() {
    }

    public static PreparedRuntimeSources prepare(
            List<RuntimeSourceInventory.Source> sources, Path cacheDirectory
    ) throws IOException {
        List<RuntimeSourceInventory.Source> inputs = List.copyOf(sources);
        if (inputs.isEmpty()) {
            throw new IOException("The runtime has no class sources");
        }
        Path cache = Objects.requireNonNull(cacheDirectory, "cacheDirectory").toAbsolutePath().normalize();
        if (cache.getFileSystem() != FileSystems.getDefault()) {
            throw new IOException("Runtime source cache must be on the default filesystem: " + cache);
        }
        return CacheFiles.locked(cache.getParent(), () -> prepareLocked(inputs, cache));
    }

    private static PreparedRuntimeSources prepareLocked(List<RuntimeSourceInventory.Source> inputs, Path cache)
            throws IOException {
        String id = fingerprint(inputs);
        Files.createDirectories(cache);
        AtomicFiles.cleanupAbandonedStaging(cache.getParent());
        AtomicFiles.cleanupAbandonedStaging(cache);
        requireCurrentLayout(cache);
        List<String> names = fileNames(inputs);
        Path manifestFile = cache.resolve(MANIFEST);
        boolean matches = Files.isRegularFile(manifestFile)
                && id.equals(JsonFiles.string(JsonFiles.read(manifestFile), "id"));
        if (!matches) {
            Path staged = Files.createTempDirectory(cache.getParent(), ".td-" + ProcessHandle.current().pid() + "-");
            try {
                JsonObject manifest = new JsonObject();
                manifest.addProperty("format", 1);
                manifest.addProperty("id", id);
                JsonArray files = new JsonArray();
                for (int i = 0; i < inputs.size(); i++) {
                    Path source = inputs.get(i).path();
                    if (!isVirtual(source)) {
                        continue;
                    }
                    Path destination = staged.resolve(names.get(i));
                    if (Files.isDirectory(source)) {
                        packClassDirectory(source, destination);
                    } else {
                        // Loader filesystems support reading without cross-provider attribute copying.
                        try (var input = Files.newInputStream(source)) {
                            Files.copy(input, destination);
                        }
                    }
                    JsonObject file = new JsonObject();
                    file.addProperty("file", names.get(i));
                    file.addProperty("logicalUri", source.toUri().toASCIIString());
                    file.addProperty("size", Files.size(destination));
                    files.add(file);
                }
                manifest.add("files", files);
                JsonFiles.write(staged.resolve(MANIFEST), manifest);
                validateManifest(staged, id, inputs, names);

                // Readers share this lock. Invalidate identity before changing any published bytes.
                Files.deleteIfExists(cache.getParent().resolve("inventory.json"));
                Files.deleteIfExists(manifestFile);
                for (String name : names) {
                    if (name != null) {
                        Files.move(staged.resolve(name), cache.resolve(name),
                                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                try (var entries = Files.list(cache)) {
                    for (Path entry : entries.toList()) {
                        if (entry.getFileName().toString().endsWith(".jar")
                                && !names.contains(entry.getFileName().toString())) {
                            Files.delete(entry);
                        }
                    }
                }
                Files.move(staged.resolve(MANIFEST), manifestFile, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } finally {
                AtomicFiles.deleteOwned(cache.getParent(), staged);
            }
        }
        validateManifest(cache, id, inputs, names);
        List<PreparedRuntimeSources.Source> prepared = new ArrayList<>(inputs.size());
        for (int i = 0; i < inputs.size(); i++) {
            var source = inputs.get(i);
            Path physical = names.get(i) == null ? source.path() : cache.resolve(names.get(i));
            prepared.add(new PreparedRuntimeSources.Source(source, physical));
        }
        return new PreparedRuntimeSources(id, cache, prepared);
    }

    private static void requireCurrentLayout(Path cache) throws IOException {
        try (var entries = Files.list(cache)) {
            for (Path entry : entries.toList()) {
                String name = entry.getFileName().toString();
                if (!Files.isRegularFile(entry, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                        || !(name.equals(MANIFEST) || name.endsWith(".jar"))) {
                    throw new IOException("Unsupported runtime source cache entry: " + entry
                            + ". Clear this generated cache manually before using the current layout.");
                }
            }
        }
    }

    private static List<String> fileNames(List<RuntimeSourceInventory.Source> inputs) {
        var used = new java.util.HashSet<String>();
        var names = new ArrayList<String>(inputs.size());
        for (var input : inputs) {
            if (!isVirtual(input.path())) {
                names.add(null);
                continue;
            }
            String label = input.path().getFileName() == null ? "" : input.path().getFileName().toString();
            if (label.endsWith(".jar")) {
                label = label.substring(0, label.length() - 4);
            } else {
                label = Objects.requireNonNullElse(input.moduleName(), "runtime");
            }
            names.add(CacheNames.uniqueStem(label, used) + ".jar");
        }
        return names;
    }

    private static void validateManifest(Path directory, String id, List<RuntimeSourceInventory.Source> inputs,
                                         List<String> names) throws IOException {
        try {
            JsonObject manifest = JsonFiles.read(directory.resolve(MANIFEST));
            if (JsonFiles.integer(manifest, "format") != 1 || !id.equals(JsonFiles.string(manifest, "id"))) {
                throw new IllegalArgumentException("Prepared-source identity or format mismatch");
            }
            JsonArray files = JsonFiles.array(manifest, "files");
            int position = 0;
            for (int i = 0; i < inputs.size(); i++) {
                if (names.get(i) == null) {
                    continue;
                }
                JsonObject entry = files.get(position++).getAsJsonObject();
                Path file = directory.resolve(names.get(i));
                if (!Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Prepared runtime source does not exist: " + file);
                }
                if (!names.get(i).equals(JsonFiles.string(entry, "file"))
                        || !inputs.get(i).path().toUri().toASCIIString().equals(JsonFiles.string(entry, "logicalUri"))
                        || Files.size(file) != entry.get("size").getAsBigDecimal().longValueExact()) {
                    throw new IllegalArgumentException("Prepared source does not match its manifest: " + file);
                }
            }
            if (position != files.size()) {
                throw new IllegalArgumentException("Unexpected prepared-source entries");
            }
        } catch (RuntimeException exception) {
            throw new IOException("Incomplete prepared runtime sources " + directory + ": " + exception.getMessage(), exception);
        }
    }

    private static String fingerprint(List<RuntimeSourceInventory.Source> sources) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
        update(digest, FORMAT);
        update(digest, System.getProperty("java.runtime.version", ""));
        update(digest, System.getProperty("java.home", ""));
        for (RuntimeSourceInventory.Source source : sources) {
            Path path = source.path();
            if (!Files.exists(path)) {
                throw new IOException("Runtime class source does not exist: " + path.toUri());
            }
            update(digest, path.toUri().toASCIIString());
            update(digest, Objects.requireNonNullElse(source.moduleName(), ""));
            if (Files.isDirectory(path)) {
                for (Path file : classFiles(path)) {
                    update(digest, entryName(path, file));
                    updateMetadata(digest, file);
                }
            } else {
                updateMetadata(digest, path);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void packClassDirectory(Path source, Path target) throws IOException {
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(target))) {
            for (Path file : classFiles(source)) {
                ZipEntry entry = new ZipEntry(entryName(source, file));
                entry.setTime(Files.getLastModifiedTime(file).toMillis());
                output.putNextEntry(entry);
                Files.copy(file, output);
                output.closeEntry();
            }
        }
    }

    private static List<Path> classFiles(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = entryName(root, path);
                        return name.endsWith(".class") || name.equals("META-INF/MANIFEST.MF");
                    })
                    .sorted()
                    .toList();
        }
    }

    private static String entryName(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    private static boolean isVirtual(Path path) {
        return path.getFileSystem() != FileSystems.getDefault();
    }

    private static void updateMetadata(MessageDigest digest, Path file) throws IOException {
        update(digest, Long.toString(Files.size(file)));
        update(digest, Long.toString(Files.getLastModifiedTime(file).toMillis()));
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }


}
