package com.github.minecraft_ta.totalDebugCompanion.catalog;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Reads single entries from a mod's original file: a JAR, a folder, or a JAR nested in other JARs, which NeoForge
 * records as {@code jij:/outer.jar%23<id>!/META-INF/jarjar/inner.jar}.
 */
public final class ModFiles {
    private static final int MAXIMUM_NESTED_JAR_BYTES = 64 * 1024 * 1024;

    private ModFiles() {
    }

    /** The bytes of {@code entry} in the mod file, or empty when the file or entry does not exist. Blocking. */
    public static Optional<byte[]> read(URI modFile, String entry, int maximumBytes) throws IOException {
        if ("file".equalsIgnoreCase(modFile.getScheme())) {
            return read(Path.of(modFile), entry, maximumBytes);
        }
        if (!"jij".equalsIgnoreCase(modFile.getScheme())) return Optional.empty();
        List<String> parts = nesting(modFile.toString());
        Path outer = outerPath(parts.getFirst());
        if (!Files.isRegularFile(outer)) return Optional.empty();
        byte[] jar;
        try (ZipFile archive = new ZipFile(outer.toFile())) {
            ZipEntry inner = archive.getEntry(parts.get(1));
            if (inner == null) return Optional.empty();
            try (InputStream input = archive.getInputStream(inner)) {
                jar = input.readNBytes(MAXIMUM_NESTED_JAR_BYTES);
            }
        }
        for (String inner : parts.subList(2, parts.size())) {
            Optional<byte[]> nested = entry(jar, inner, MAXIMUM_NESTED_JAR_BYTES);
            if (nested.isEmpty()) return Optional.empty();
            jar = nested.get();
        }
        return entry(jar, entry, maximumBytes);
    }

    private static Optional<byte[]> read(Path file, String entry, int maximumBytes) throws IOException {
        if (Files.isDirectory(file)) {
            Path path = file.resolve(entry).normalize();
            if (!path.startsWith(file) || !Files.isRegularFile(path) || Files.size(path) > maximumBytes) return Optional.empty();
            return Optional.of(Files.readAllBytes(path));
        }
        if (!Files.isRegularFile(file)) return Optional.empty();
        try (ZipFile archive = new ZipFile(file.toFile())) {
            ZipEntry found = archive.getEntry(entry);
            if (found == null || found.isDirectory()) return Optional.empty();
            try (InputStream input = archive.getInputStream(found)) {
                return Optional.of(input.readNBytes(maximumBytes));
            }
        }
    }

    /** The outer file's text and each nested entry name, with the {@code %23<id>} markers removed. */
    static List<String> nesting(String uri) {
        String path = uri.substring(uri.indexOf(':') + 1);
        List<String> parts = new ArrayList<>();
        for (String part : path.split("!/")) parts.add(part.replaceFirst("%23\\d+$", ""));
        if (parts.size() < 2) throw new IllegalArgumentException("Not a nested mod file: " + uri);
        return parts;
    }

    private static Path outerPath(String encoded) {
        String decoded = URLDecoder.decode(encoded.replace("+", "%2B"), StandardCharsets.UTF_8);
        if (decoded.matches("/[A-Za-z]:/.*")) decoded = decoded.substring(1);
        return Path.of(decoded);
    }

    private static Optional<byte[]> entry(byte[] jar, String name, int maximumBytes) throws IOException {
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(jar))) {
            for (ZipEntry entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) {
                if (entry.getName().equals(name) && !entry.isDirectory()) return Optional.of(input.readNBytes(maximumBytes));
            }
        }
        return Optional.empty();
    }
}
