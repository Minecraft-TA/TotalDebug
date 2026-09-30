package com.github.minecraft_ta.totaldebug.client.inspection;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * What an icon archive holds, as a key: the game's version, each enabled pack, lowest first, with the size and time of
 * its files, and the fluids' appearances. The same key means the same winning resources, so the archive captured for it
 * is used again, also by a later run. File times say where to look again; they prove nothing: a pack whose files cannot
 * be found, such as one a mod builds in memory, or a file written a moment ago, gives no key, and the archive is
 * captured again.
 */
final class IconArchiveKey {
    /**
     * A file written this recently is not trusted: a write in the same moment the key is taken can leave its size and
     * time as they were.
     */
    static final Duration SETTLED = Duration.ofSeconds(2);

    /**
     * One enabled pack: its id, and the folders and files its resources come from, or none for the game's own, whose
     * files a version does not change. {@code sources} null means the files cannot be found.
     */
    record Part(String id, List<Path> sources) {
        static Part builtIn(String id) {
            return new Part(id, List.of());
        }

        static Part unknown(String id) {
            return new Part(id, null);
        }
    }

    private IconArchiveKey() {
    }

    /** The key of {@code packs} for {@code version} and {@code fluids}, as of {@code now}, or empty when there is none. */
    static Optional<String> of(String version, List<Part> packs, byte[] fluids, Instant now) {
        MessageDigest digest = sha256();
        text(digest, version);
        for (Part part : packs) {
            text(digest, part.id());
            if (part.sources() == null) return Optional.empty();
            for (Path source : part.sources()) {
                if (!stamp(digest, source, now)) return Optional.empty();
            }
        }
        digest.update(fluids);
        return Optional.of(HexFormat.of().formatHex(digest.digest()));
    }

    /**
     * Adds the files of {@code source}, a folder or a file; false when it is gone, cannot be read or changed a moment ago.
     * Links to folders, the pack's own or inside it, are followed, as the game reads through them; links that loop give
     * no key.
     */
    private static boolean stamp(MessageDigest digest, Path source, Instant now) {
        try {
            if (Files.isRegularFile(source)) return stampFile(digest, source.toString(), source, now);
            if (!Files.isDirectory(source)) return false;
            Path root = source.getFileSystem() == FileSystems.getDefault() ? source.toRealPath() : source;
            List<Path> files;
            try (Stream<Path> walk = Files.walk(root, FileVisitOption.FOLLOW_LINKS)) {
                files = walk.filter(Files::isRegularFile).sorted().toList();
            }
            text(digest, source.toString());
            for (Path file : files) {
                if (!stampFile(digest, root.relativize(file).toString().replace('\\', '/'), file, now)) return false;
            }
            return true;
        } catch (IOException | UncheckedIOException unreadable) {
            return false;
        }
    }

    private static boolean stampFile(MessageDigest digest, String name, Path file, Instant now) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
        Instant modified = attributes.lastModifiedTime().toInstant();
        if (modified.isAfter(now.minus(SETTLED))) return false;
        text(digest, name);
        text(digest, Long.toString(attributes.size()));
        text(digest, modified.toString());
        return true;
    }

    private static void text(MessageDigest digest, String text) {
        digest.update(text.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", missing);
        }
    }
}
