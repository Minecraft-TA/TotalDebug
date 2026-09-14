package com.github.minecraft_ta.totalDebugCompanion.bytecode;

import com.github.minecraft_ta.totalDebugCompanion.runtime.IndexIdentity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.io.UncheckedIOException;
import java.util.function.Consumer;

/** Validates a local snapshot once, then checks file metadata before serving its bytes. */
public final class LocalSourceGuard {
    /** Content and metadata captured together; only capture() can construct one. */
    public static final class Fingerprint {
        private final String hash;
        private final Stamp stamp;

        private Fingerprint(String hash, Stamp stamp) { this.hash = hash; this.stamp = stamp; }
    }

    private record Stamp(long size, FileTime modified, Object fileKey) {
        static Stamp read(Path path) throws IOException {
            var attributes = Files.readAttributes(path, BasicFileAttributes.class);
            return new Stamp(attributes.size(), attributes.lastModifiedTime(), attributes.fileKey());
        }
    }

    private final IndexIdentity identity;
    private final Map<Path, Stamp> verified = new HashMap<>();
    private volatile IOException failure;
    private Consumer<IOException> onInvalidated = ignored -> { };

    public static Fingerprint capture(Path path) throws IOException {
        Stamp before = Stamp.read(path);
        String hash = IndexIdentity.fingerprint(path);
        if (!before.equals(Stamp.read(path))) throw new IOException("Mod archive changed during validation: " + path);
        return new Fingerprint(hash, before);
    }

    public LocalSourceGuard(Map<Path, Fingerprint> fingerprints) {
        this.identity = IndexIdentity.local(fingerprints.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().hash)));
        fingerprints.forEach((path, fingerprint) -> verified.put(path, fingerprint.stamp));
    }

    public IndexIdentity identity() { return identity; }

    public synchronized void onInvalidated(Consumer<IOException> listener) { onInvalidated = listener; }
    public boolean isValid() { return failure == null; }
    public void requireValid() { if (failure != null) throw new UncheckedIOException(failure); }

    public synchronized void checkAll() throws IOException {
        for (Path path : identity.fingerprints().keySet()) check(path);
    }

    synchronized void check(Path path) throws IOException {
        if (failure != null) throw failure;
        try {
            if (identity.fingerprints().containsKey(path) && !Stamp.read(path).equals(verified.get(path))) validate(path);
        } catch (IOException changed) {
            failure = changed;
            onInvalidated.accept(changed);
            throw changed;
        }
    }

    private void validate(Path path) throws IOException {
        Stamp before = Stamp.read(path);
        identity.requireSourceUnchanged(path);
        if (!before.equals(Stamp.read(path))) throw new IOException("Mod archive changed during validation: " + path);
        verified.put(path, before);
    }
}
