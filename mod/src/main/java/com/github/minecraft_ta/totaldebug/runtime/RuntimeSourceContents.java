package com.github.minecraft_ta.totaldebug.runtime;

import cpw.mods.niofs.union.UnionFileSystem;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.zip.ZipInputStream;

/** Content identity of the effective loader view, independent of provider instance counters. */
final class RuntimeSourceContents {
    private RuntimeSourceContents() { }

    static List<Path> classFiles(Path root) throws IOException {
        checkInterrupted();
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).filter(path -> retained(entryName(root, path)))
                    .sorted(Comparator.comparing(path -> entryName(root, path))).toList();
        }
    }

    static String directoryFingerprint(Path root) throws IOException {
        Map<String, String> entries = new TreeMap<>();
        byte[] buffer = new byte[65536];
        for (Path file : classFiles(root)) {
            try (var input = Files.newInputStream(file)) {
                entries.put(entryName(root, file), streamFingerprint(input, buffer));
            }
        }
        return entriesFingerprint(entries);
    }

    static String archiveFingerprint(Path archive) throws IOException {
        Map<String, String> entries = new TreeMap<>();
        byte[] buffer = new byte[65536];
        try (var input = new ZipInputStream(new java.io.BufferedInputStream(Files.newInputStream(archive), 65536))) {
            for (var entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) {
                checkInterrupted();
                if (!entry.isDirectory() && retained(entry.getName())) {
                    if (entries.putIfAbsent(entry.getName(), streamFingerprint(input, buffer)) != null) {
                        return null; // Duplicate ZIP names cannot prove one unambiguous loader view.
                    }
                }
            }
        }
        return entriesFingerprint(entries);
    }

    static Path equivalentArchive(Path root, String fingerprint) throws IOException {
        if (!(root.getFileSystem() instanceof UnionFileSystem union)) {
            return null;
        }
        Path candidate = union.getPrimaryPath().toAbsolutePath().normalize();
        // The primary path is only a candidate. Filters and overlays can change its effective classes.
        return Files.isRegularFile(candidate) && fingerprint.equals(archiveFingerprint(candidate)) ? candidate : null;
    }

    static String fileFingerprint(Path file) throws IOException {
        try (var input = Files.newInputStream(file)) {
            return streamFingerprint(input, new byte[65536]);
        }
    }

    private static String streamFingerprint(InputStream input, byte[] buffer) throws IOException {
        MessageDigest digest = digest();
        for (int count; (count = input.read(buffer)) != -1;) {
            checkInterrupted();
            digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String entriesFingerprint(Map<String, String> entries) {
        MessageDigest digest = digest();
        update(digest, "runtime-class-view-1");
        entries.forEach((name, hash) -> { update(digest, name); update(digest, hash); });
        return HexFormat.of().formatHex(digest.digest());
    }

    static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    static String entryName(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private static boolean retained(String name) {
        return name.endsWith(".class") || name.equals("META-INF/MANIFEST.MF");
    }

    static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Runtime source preparation was interrupted");
        }
    }
}
