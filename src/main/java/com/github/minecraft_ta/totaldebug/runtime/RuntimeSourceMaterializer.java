package com.github.minecraft_ta.totaldebug.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Materializes virtual class roots without teaching consumers about loader-specific filesystems. */
public final class RuntimeSourceMaterializer {
    private static final String FORMAT = "1";
    private static final String COMPLETE = "complete";

    private RuntimeSourceMaterializer() {
    }

    public static PreparedRuntimeSources prepare(
            List<RuntimeSourceInventory.Source> sources,
            Path cacheDirectory
    ) throws IOException {
        sources = List.copyOf(sources);
        if (sources.isEmpty()) {
            throw new IOException("The runtime has no class sources");
        }
        Path cache = Objects.requireNonNull(cacheDirectory, "cacheDirectory").toAbsolutePath().normalize();
        if (cache.getFileSystem() != FileSystems.getDefault()) {
            throw new IOException("Runtime source cache must be on the default filesystem: " + cache);
        }
        String id = fingerprint(sources);
        Path published = cache.resolve(id);
        boolean needsMaterialization = sources.stream().anyMatch(source -> isVirtual(source.path()));
        if (needsMaterialization && !Files.exists(published)) {
            Files.createDirectories(cache);
            Path staged = Files.createTempDirectory(cache, ".preparing-");
            try {
                for (int index = 0; index < sources.size(); index++) {
                    Path source = sources.get(index).path();
                    if (!isVirtual(source)) {
                        continue;
                    }
                    Path destination = staged.resolve(fileName(index));
                    if (Files.isDirectory(source)) {
                        packClassDirectory(source, destination);
                    } else {
                        // Loader filesystems can read bytes without supporting cross-provider attribute copying.
                        try (var input = Files.newInputStream(source)) {
                            Files.copy(input, destination);
                        }
                    }
                }
                Files.writeString(staged.resolve(COMPLETE), id, StandardCharsets.UTF_8);
                try {
                    Files.move(staged, published, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException exception) {
                    throw new IOException("Runtime source cache does not support atomic publication: " + cache, exception);
                }
            } finally {
                deleteStagingDirectory(staged);
            }
        }
        if (needsMaterialization && (!Files.isRegularFile(published.resolve(COMPLETE))
                || !id.equals(Files.readString(published.resolve(COMPLETE), StandardCharsets.UTF_8)))) {
            throw new IOException("Incomplete prepared runtime sources: " + published);
        }
        List<PreparedRuntimeSources.Source> prepared = new ArrayList<>(sources.size());
        for (int index = 0; index < sources.size(); index++) {
            RuntimeSourceInventory.Source source = sources.get(index);
            Path physical = isVirtual(source.path()) ? published.resolve(fileName(index)) : source.path();
            if (!Files.exists(physical)) {
                throw new IOException("Prepared runtime source does not exist: " + physical);
            }
            prepared.add(new PreparedRuntimeSources.Source(source, physical));
        }
        return new PreparedRuntimeSources(id, prepared);
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

    private static String fileName(int index) {
        return "source-" + index + ".jar";
    }

    private static void updateMetadata(MessageDigest digest, Path file) throws IOException {
        update(digest, Long.toString(Files.size(file)));
        update(digest, Long.toString(Files.getLastModifiedTime(file).toMillis()));
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static void deleteStagingDirectory(Path staged) throws IOException {
        if (!Files.exists(staged)) {
            return;
        }
        try (var paths = Files.walk(staged)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
