package com.github.minecraft_ta.totalDebugCompanion.storage;

import com.github.minecraft_ta.totaldebug.storage.AtomicFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Contents Companion replaced in the pack, kept by their SHA-256 in {@code total-debug/originals} so a resource change
 * can be reverted after Companion or the game restarted. An empty hash stands for no content.
 */
public final class ResourceOriginals {
    private final Path directory;

    public ResourceOriginals(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory");
    }

    /** The SHA-256 of {@code content} in lowercase hex, or empty for null. */
    public static String hash(byte[] content) {
        if (content == null) return "";
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException(missing);
        }
    }

    /** Keeps {@code content} and returns its hash; null keeps nothing and returns empty. Blocking. */
    public String keep(byte[] content) throws IOException {
        String hash = hash(content);
        if (content == null) return hash;
        Path file = this.directory.resolve(hash);
        if (!Files.isRegularFile(file)) AtomicFiles.replace(file, staged -> Files.write(staged, content));
        return hash;
    }

    /** The content kept under {@code hash}, or null for the empty hash. Blocking. */
    public byte[] read(String hash) throws IOException {
        if (hash.isEmpty()) return null;
        Path file = this.directory.resolve(hash);
        if (!Files.isRegularFile(file)) throw new IOException("The original content " + hash + " is missing from " + this.directory);
        byte[] content = Files.readAllBytes(file);
        if (!hash(content).equals(hash)) throw new IOException("The original content " + file + " is damaged");
        return content;
    }
}
