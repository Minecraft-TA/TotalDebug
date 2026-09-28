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
import java.util.jar.JarFile;
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
        Optional<byte[]> jar = nestedJar(modFile);
        return jar.isEmpty() ? Optional.empty() : entry(jar.get(), entry, maximumBytes);
    }

    /**
     * The bytes of a nested mod file, read through the files it is nested in; empty when one of them is gone. A file
     * larger than Companion reads is refused rather than cut off, which would leave it unreadable without saying why.
     */
    private static Optional<byte[]> nestedJar(URI modFile) throws IOException {
        List<String> parts = nesting(modFile.toString());
        Path outer = outerPath(parts.getFirst());
        if (!Files.isRegularFile(outer)) return Optional.empty();
        byte[] jar;
        try (ZipFile archive = new ZipFile(outer.toFile())) {
            ZipEntry inner = archive.getEntry(parts.get(1));
            if (inner == null) return Optional.empty();
            try (InputStream input = archive.getInputStream(inner)) {
                jar = whole(input.readNBytes(MAXIMUM_NESTED_JAR_BYTES + 1), parts.get(1));
            }
        }
        for (String inner : parts.subList(2, parts.size())) {
            Optional<byte[]> nested = entry(jar, inner, MAXIMUM_NESTED_JAR_BYTES + 1);
            if (nested.isEmpty()) return Optional.empty();
            jar = whole(nested.get(), inner);
        }
        return Optional.of(jar);
    }

    private static byte[] whole(byte[] jar, String name) throws IOException {
        if (jar.length > MAXIMUM_NESTED_JAR_BYTES) throw tooLarge(name, MAXIMUM_NESTED_JAR_BYTES);
        return jar;
    }

    /**
     * {@code entry}'s bytes from {@code input}, at most {@code maximumBytes}: a larger entry is refused with its size
     * limit rather than cut off, which would leave it unreadable without saying why.
     */
    private static byte[] limited(InputStream input, int maximumBytes, String entry) throws IOException {
        byte[] bytes = input.readNBytes(maximumBytes == Integer.MAX_VALUE ? maximumBytes : maximumBytes + 1);
        if (bytes.length > maximumBytes) throw tooLarge(entry, maximumBytes);
        return bytes;
    }

    private static IOException tooLarge(String entry, int maximumBytes) {
        String limit = maximumBytes >= 1024 * 1024 ? maximumBytes / (1024 * 1024) + " MiB" : maximumBytes / 1024 + " KiB";
        return new IOException(entry + " is larger than " + limit + ", which Companion reads");
    }

    /** A mod file opened for many reads, closed when done. */
    public interface Archive extends AutoCloseable {
        /** The bytes of {@code entry}, or empty when the file has none. Blocking. */
        Optional<byte[]> read(String entry, int maximumBytes) throws IOException;

        @Override
        void close() throws IOException;
    }

    /**
     * Opens the mod file for many reads: a JAR stays open, and a nested JAR is copied to a temporary file once, which is
     * read entry by entry and deleted on close. A multi-release JAR gives the entries this Java version loads, as the game
     * does. Empty when the file does not exist. Blocking.
     */
    public static Optional<Archive> open(URI modFile) throws IOException {
        if ("file".equalsIgnoreCase(modFile.getScheme())) {
            Path file = Path.of(modFile);
            if (Files.isDirectory(file)) return Optional.of(new Archive() {
                @Override
                public Optional<byte[]> read(String entry, int maximumBytes) throws IOException {
                    return ModFiles.read(file, entry, maximumBytes);
                }

                @Override
                public void close() {
                }
            });
            if (!Files.isRegularFile(file)) return Optional.empty();
            return Optional.of(jar(file, null));
        }
        if (!"jij".equalsIgnoreCase(modFile.getScheme())) return Optional.empty();
        Optional<byte[]> nested = nestedJar(modFile);
        if (nested.isEmpty()) return Optional.empty();
        byte[] jar = nested.get();
        // A copy on disk is read like any JAR, one entry at a time, however much its entries would expand to.
        Path copy = Files.createTempFile("totaldebug-nested", ".jar");
        try {
            Files.write(copy, jar);
            return Optional.of(jar(copy, copy));
        } catch (IOException | RuntimeException failed) {
            Files.deleteIfExists(copy);
            throw failed;
        }
    }

    /**
     * Deletes a temporary copy once read. What was read from it stands if another process still holds it: it is deleted
     * when Companion exits instead.
     */
    private static void delete(Path copy) {
        try {
            Files.deleteIfExists(copy);
        } catch (IOException held) {
            copy.toFile().deleteOnExit();
        }
    }

    /** The JAR {@code file}, read at this Java version's entries; {@code copy}, if not null, is deleted on close. */
    private static Archive jar(Path file, Path copy) throws IOException {
        JarFile archive = new JarFile(file.toFile(), false, ZipFile.OPEN_READ, Runtime.version());
        return new Archive() {
            @Override
            public Optional<byte[]> read(String entry, int maximumBytes) throws IOException {
                ZipEntry found = archive.getEntry(entry);
                if (found == null || found.isDirectory()) return Optional.empty();
                try (InputStream input = archive.getInputStream(found)) {
                    return Optional.of(limited(input, maximumBytes, entry));
                }
            }

            @Override
            public void close() throws IOException {
                try {
                    archive.close();
                } finally {
                    if (copy != null) delete(copy);
                }
            }
        };
    }

    private static Optional<byte[]> read(Path file, String entry, int maximumBytes) throws IOException {
        if (Files.isDirectory(file)) {
            Path path = file.resolve(entry).normalize();
            if (!path.startsWith(file) || !Files.isRegularFile(path)) return Optional.empty();
            if (Files.size(path) > maximumBytes) throw tooLarge(entry, maximumBytes);
            return Optional.of(Files.readAllBytes(path));
        }
        if (!Files.isRegularFile(file)) return Optional.empty();
        try (ZipFile archive = new ZipFile(file.toFile())) {
            ZipEntry found = archive.getEntry(entry);
            if (found == null || found.isDirectory()) return Optional.empty();
            try (InputStream input = archive.getInputStream(found)) {
                return Optional.of(limited(input, maximumBytes, entry));
            }
        }
    }

    /** The outer file's text and each nested entry name, with the {@code %23<id>} markers removed. */
    static List<String> nesting(String uri) {
        String path = uri.substring(uri.indexOf(':') + 1);
        List<String> parts = new ArrayList<>();
        for (String part : path.split("!/")) {
            String entry = part.replaceFirst("%23\\d+$", "");
            // Entry names inside a file are written as in a URI, such as a space as %20; the outer path is decoded apart.
            parts.add(parts.isEmpty() ? entry : URLDecoder.decode(entry.replace("+", "%2B"), StandardCharsets.UTF_8));
        }
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
                if (entry.getName().equals(name) && !entry.isDirectory()) return Optional.of(limited(input, maximumBytes, name));
            }
        }
        return Optional.empty();
    }
}
