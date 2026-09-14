package com.github.minecraft_ta.totalDebugCompanion.runtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

/** Separates a local source fingerprint from a Minecraft-published inventory identity. */
public record IndexIdentity(Kind kind, String value, Map<Path, String> fingerprints) {
    public enum Kind { LOCAL, RUNTIME }

    public IndexIdentity {
        Objects.requireNonNull(kind);
        if (Objects.requireNonNull(value).isBlank()) throw new IllegalArgumentException("Index identity is blank");
        fingerprints = Map.copyOf(fingerprints);
        if (kind == Kind.RUNTIME && !fingerprints.isEmpty()) throw new IllegalArgumentException("Runtime identity must come from its inventory");
    }

    public static IndexIdentity runtime(String inventoryId) { return new IndexIdentity(Kind.RUNTIME, inventoryId, Map.of()); }

    public static IndexIdentity local(Map<Path, String> fingerprints) {
        var digest = digest();
        update(digest, System.getProperty("java.home"));
        update(digest, System.getProperty("java.runtime.version"));
        fingerprints.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            update(digest, entry.getKey().toUri().toString());
            update(digest, entry.getValue());
        });
        return new IndexIdentity(Kind.LOCAL, HexFormat.of().formatHex(digest.digest()), fingerprints);
    }

    public String signature() { return IndexCache.FORMAT + ":" + kind + ":" + value; }

    public void requireSourcesUnchanged() throws IOException {
        for (var entry : fingerprints.entrySet()) requireSourceUnchanged(entry.getKey());
    }

    public void requireSourceUnchanged(Path path) throws IOException {
        String expected = fingerprints.get(path);
        if (expected != null && !expected.equals(fingerprint(path))) {
            throw new IOException("Mod archive changed; reopen the project to rebuild its index: " + path);
        }
    }

    public static String fingerprint(Path path) throws IOException {
        var digest = digest();
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("Source hashing interrupted");
                digest.update(buffer, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void update(MessageDigest digest, String value) {
        digest.update(value.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
