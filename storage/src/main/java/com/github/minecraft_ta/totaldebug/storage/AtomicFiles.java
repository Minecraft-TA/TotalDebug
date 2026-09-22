package com.github.minecraft_ta.totaldebug.storage;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Publication and cleanup mechanics; callers own formats and retention decisions. */
public final class AtomicFiles {
    private static final String STAGING_PREFIX = ".td-" + ProcessHandle.current().pid() + "-";
    private static final Pattern STAGING_NAME = Pattern.compile("\\.td-(\\d+)-.+");

    private AtomicFiles() {
    }

    @FunctionalInterface
    public interface Writer {
        void write(Path staged) throws IOException;
    }

    public static void writeString(Path target, String value) throws IOException {
        replace(target, staged -> Files.writeString(staged, value, StandardCharsets.UTF_8));
    }

    /** Atomically creates a new file without overwriting another writer's file. */
    public static void createNewString(Path target, String value) throws IOException {
        Path destination = normalized(target);
        Path staged = temporaryFile(destination.getParent());
        try {
            Files.writeString(staged, value, StandardCharsets.UTF_8);
            Files.createLink(destination, staged);
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    public static Path temporaryFile(Path directory) throws IOException {
        Path parent = normalized(directory);
        Files.createDirectories(parent);
        return Files.createTempFile(parent, STAGING_PREFIX, ".tmp");
    }

    /** Restrict access before writing credentials, including on Windows ACL filesystems. */
    public static void writeSecret(Path target, String value) throws IOException {
        replace(target, staged -> {
            var acl = Files.getFileAttributeView(staged, AclFileAttributeView.class);
            if (acl != null) {
                String user = System.getProperty("user.name");
                String domain = System.getenv("USERDOMAIN");
                String account = domain == null || domain.isBlank() ? user : domain + "\\" + user;
                var principal = staged.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(account);
                acl.setAcl(List.of(AclEntry.newBuilder()
                        .setType(AclEntryType.ALLOW)
                        .setPrincipal(principal)
                        .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                        .build()));
            } else if (Files.getFileAttributeView(staged, PosixFileAttributeView.class) != null) {
                Files.setPosixFilePermissions(staged, Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE));
            } else {
                throw new IOException("Credential storage requires ACL or POSIX permissions: " + staged);
            }
            Files.writeString(staged, value, StandardCharsets.US_ASCII);
        });
    }

    /** The writer must finish and validate the entire file before returning. */
    public static void replace(Path target, Writer writer) throws IOException {
        Path destination = normalized(target);
        Path parent = destination.getParent();
        Files.createDirectories(parent);
        Path staged = Files.createTempFile(parent, STAGING_PREFIX, ".tmp");
        try {
            writer.write(staged);
            if (!Files.isRegularFile(staged, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Staged output is not a regular file: " + staged);
            }
            replaceStagedFile(staged, destination);
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    private static void replaceStagedFile(Path staged, Path destination) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (true) {
            try {
                Files.move(staged, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (AccessDeniedException denied) {
                // Windows cannot replace a file while a reader holds it open. Keep the old
                // publication intact and retry only the move, never the writer's work.
                if (destination.getFileSystem().getSeparator().equals("\\") && System.nanoTime() < deadline) {
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        var failure = new InterruptedIOException("Interrupted while replacing " + destination);
                        failure.initCause(denied);
                        throw failure;
                    }
                } else {
                    throw denied;
                }
            }
        }
    }

    /** Publishes a new immutable directory, never a delete-then-replace operation. */
    public static void publishDirectory(Path target, Writer writer) throws IOException {
        Path destination = normalized(target);
        Path parent = destination.getParent();
        Files.createDirectories(parent);
        Path staged = Files.createTempDirectory(parent, STAGING_PREFIX);
        try {
            writer.write(staged);
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Cache entry already exists: " + destination);
            }
            Files.move(staged, destination, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            deleteOwned(parent, staged);
        }
    }

    /** Deletes a strict descendant without following symbolic links outside the owned root. */
    public static void deleteOwned(Path ownedRoot, Path target) throws IOException {
        Path root = normalized(ownedRoot);
        Path destination = normalized(target);
        if (destination.equals(root) || !destination.startsWith(root)) {
            throw new IOException("Refusing to delete outside the owned directory: " + destination);
        }
        if (!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Path realRoot = root.toRealPath();
        if (!destination.getParent().toRealPath().startsWith(realRoot)) {
            throw new IOException("Cache path crosses outside its owned directory: " + destination);
        }
        try (var paths = Files.walk(destination)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    /** Only our staging names from dead processes are eligible, never arbitrary temp files. */
    public static void cleanupAbandonedStaging(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (var children = Files.list(directory)) {
            for (Path child : children.toList()) {
                var match = STAGING_NAME.matcher(child.getFileName().toString());
                if (!match.matches()) {
                    continue;
                }
                long pid;
                try {
                    pid = Long.parseLong(match.group(1));
                } catch (NumberFormatException invalid) {
                    continue;
                }
                if (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                    continue;
                }
                deleteOwned(directory, child);
            }
        }
    }

    private static Path normalized(Path path) {
        Path result = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        if (result.getParent() == null) {
            throw new IllegalArgumentException("A filesystem root is not an output file: " + result);
        }
        return result;
    }
}
