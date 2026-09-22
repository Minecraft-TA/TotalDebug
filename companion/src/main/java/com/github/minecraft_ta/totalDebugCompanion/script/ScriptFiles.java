package com.github.minecraft_ta.totalDebugCompanion.script;

import com.github.minecraft_ta.totalDebugCompanion.jdt.JavaSnippetSource;
import com.github.minecraft_ta.totaldebug.storage.AtomicFiles;

import java.io.IOException;
import java.awt.Desktop;
import java.nio.file.Files;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Locale;
import java.util.List;
import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.FileVisitResult;

/** Files owned by one project's Scripts root. Call filesystem operations off the EDT. */
public final class ScriptFiles {
    public static final String EXTENSION = ".tdscript";
    // Content participates in conflict detection even when an external editor preserves metadata.
    public record Version(Object key, FileTime modified, long size, String text) { }
    public record Loaded(String text, Version version) { }
    private final Path root;

    public ScriptFiles(Path root) { this.root = root.toAbsolutePath().normalize(); }
    public Path root() { return root; }
    public boolean contains(Path path) { return path.toAbsolutePath().normalize().startsWith(root); }

    /** Project-relative action references. Do not descend symlinks or Windows reparse directories. */
    public List<String> listScripts() throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return List.of();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Not a directory: " + root);
        var names = new ArrayList<String>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                return attributes.isSymbolicLink() || attributes.isOther() ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (attributes.isRegularFile() && file.getFileName().toString().endsWith(EXTENSION))
                    names.add(root.relativize(file).toString().replace('\\', '/'));
                return FileVisitResult.CONTINUE;
            }
        });
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(names);
    }

    public Path resolve(Path path) throws IOException {
        Path result = (path.isAbsolute() ? path : root.resolve(path)).toAbsolutePath().normalize();
        if (!result.startsWith(root)) throw new IOException("Choose a location inside Scripts.");
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            var attributes = Files.readAttributes(root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || attributes.isOther())
                throw new IOException("The Scripts root is linked to another location and cannot be modified.");
        }
        Path existing = result;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) existing = existing.getParent();
        Path realRoot = Files.exists(root) ? root.toRealPath() : root.getParent().toRealPath().resolve(root.getFileName());
        if (existing == null || !existing.toRealPath().startsWith(Files.exists(root) ? realRoot : realRoot.getParent()))
            throw new IOException("The path points outside Scripts.");
        if (Files.exists(root) && existing.startsWith(root)
                && !existing.toRealPath().equals(realRoot.resolve(root.relativize(existing))))
            throw new IOException("Linked files and folders cannot be modified here.");
        if (Files.isSymbolicLink(result)) throw new IOException("Linked files and folders cannot be modified here.");
        return result;
    }

    public Path child(Path parent, String name, boolean script) throws IOException {
        validateName(name, script);
        return resolve(resolve(parent).resolve(name + (script ? EXTENSION : "")));
    }

    public static void validateName(String name, boolean script) throws IOException {
        String stem = name.split("\\.", 2)[0].toUpperCase(Locale.ROOT);
        if (name.isBlank() || name.equals(".") || name.equals("..") || name.endsWith(".") || name.endsWith(" ")
                || name.chars().anyMatch(c -> c < 32 || "<>:\"/\\|?*".indexOf(c) >= 0)
                || stem.matches("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]")) throw new IOException("Use a valid file or folder name.");
        if (script && !JavaSnippetSource.isValidClassName(name)) throw new IOException("Use a valid Java identifier for a script name.");
    }

    public Path create(Path parent, String name, boolean folder, String text) throws IOException {
        Files.createDirectories(root);
        Path destination = child(parent, name, !folder);
        if (!Files.isDirectory(destination.getParent())) throw new IOException("The destination folder no longer exists.");
        try {
            if (folder) Files.createDirectory(destination);
            else AtomicFiles.createNewString(destination, text);
        } catch (FileAlreadyExistsException collision) {
            throw new IOException("\"" + destination.getFileName() + "\" already exists.", collision);
        }
        return destination;
    }

    public Loaded read(Path path) throws IOException {
        path = resolve(path);
        Version before = metadata(path);
        String text = Files.readString(path);
        if (!before.equals(metadata(path))) throw new IOException("The file changed while it was being opened. Try again.");
        return new Loaded(text, new Version(before.key(), before.modified(), before.size(), text));
    }

    public Version save(Path path, String text, Version expected) throws IOException {
        path = resolve(path);
        var current = read(path);
        if (!Objects.equals(expected, current.version())) throw new IOException("The script changed outside this editor. Reopen it before saving.");
        if (current.text().equals(text)) return expected;
        AtomicFiles.writeString(path, text);
        var saved = read(path);
        if (!saved.text().equals(text)) throw new IOException("The script changed outside this editor while saving. Reopen it before saving again.");
        return saved.version();
    }

    private Version metadata(Path path) throws IOException {
        var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) throw new IOException("The script was moved or deleted. Reopen it from Scripts.");
        return new Version(attributes.fileKey(), attributes.lastModifiedTime(), attributes.size(), null);
    }

    public Path move(Path from, Path to) throws IOException {
        from = mutable(from);
        to = resolve(to);
        validateName(to.getFileName().toString(), false);
        if (!Files.isDirectory(from) && from.getFileName().toString().endsWith(EXTENSION)) {
            if (!to.getFileName().toString().endsWith(EXTENSION)) throw new IOException("Keep the .tdscript extension.");
            validateName(to.getFileName().toString().substring(0, to.getFileName().toString().length() - EXTENSION.length()), true);
        }
        if (!Files.isDirectory(from) && to.getFileName().toString().endsWith(EXTENSION))
            validateName(to.getFileName().toString().substring(0, to.getFileName().toString().length() - EXTENSION.length()), true);
        if (from.toString().equals(to.toString())) return from;
        if (to.startsWith(from) && !to.equals(from)) throw new IOException("A folder cannot be moved into itself.");
        if (!Files.isDirectory(to.getParent())) throw new IOException("The destination folder no longer exists.");
        if (Files.exists(to, LinkOption.NOFOLLOW_LINKS)) {
            boolean caseOnly = from.getParent().equals(to.getParent()) && from.getFileName().toString().equalsIgnoreCase(to.getFileName().toString())
                    && Files.isSameFile(from, to);
            if (!caseOnly) throw new IOException("A file or folder with that name already exists.");
            // Windows treats case-only NIO moves as no-ops. Use a unique intermediate name.
            Path staged = from.resolveSibling(".td-rename-" + UUID.randomUUID());
            Files.move(from, staged);
            try { return Files.move(staged, to); }
            catch (IOException failure) {
                try { Files.move(staged, from); }
                catch (IOException restore) { throw new IOException("Rename failed; the original remains at " + staged, restore); }
                throw failure;
            }
        }
        // No REPLACE_EXISTING: a late destination collision must fail, never overwrite.
        return Files.move(from, to);
    }

    public void delete(Path path, boolean recycle) throws IOException {
        path = mutable(path);
        if (recycle) {
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH))
                throw new IOException("The recycle bin is unavailable. Choose permanent deletion explicitly.");
            if (!Desktop.getDesktop().moveToTrash(path.toFile())) throw new IOException("Unable to move to the recycle bin: " + path);
        } else {
            Path realRoot = root.toRealPath();
            Files.walkFileTree(path, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                    if (!directory.toRealPath().equals(realRoot.resolve(root.relativize(directory)))) throw new IOException("Folder links outside Scripts: " + directory);
                    return FileVisitResult.CONTINUE;
                }
            });
            AtomicFiles.deleteOwned(root, path);
        }
    }

    public Path mutable(Path path) throws IOException {
        path = resolve(path);
        if (path.equals(root)) throw new IOException("The Scripts root cannot be renamed, moved or deleted.");
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("The file or folder no longer exists: " + path);
        return path;
    }

    public static Path relocated(Path path, Path from, Path to) {
        return path.startsWith(from) ? to.resolve(from.relativize(path)) : path;
    }
}
