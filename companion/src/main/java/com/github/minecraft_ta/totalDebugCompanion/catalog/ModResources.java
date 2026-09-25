package com.github.minecraft_ta.totalDebugCompanion.catalog;

import com.github.minecraft_ta.totalDebugCompanion.navigation.NavigationTarget;
import com.github.minecraft_ta.totaldebug.storage.RuntimeInventory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The resources a mod file ships: entries under {@code assets/<namespace>/} and {@code data/<namespace>/}, grouped by
 * their first folder, such as {@code textures}, {@code models} or {@code recipe}. Listings are cached per file until
 * its size or modification time changes.
 */
public final class ModResources {
    private static final int MAX_CACHED_FILES = 64;

    /** A top-level resource folder; {@code root} is {@code assets} or {@code data}. */
    public record Category(String root, String folder) {
        public Category {
            Objects.requireNonNull(root, "root");
            Objects.requireNonNull(folder, "folder");
        }

        /** The text form used by navigation, for example {@code assets/textures}. */
        public String key() {
            return this.root + "/" + this.folder;
        }

        public static Category parse(String key) {
            int separator = key.indexOf('/');
            if (separator <= 0 || separator == key.length() - 1) {
                throw new IllegalArgumentException("Invalid resource category: " + key);
            }
            return new Category(key.substring(0, separator), key.substring(separator + 1));
        }
    }

    /** One resource inside a mod file; {@code path} is relative to the file's root. */
    public record Resource(Path file, boolean archive, String path, Category category) {
        public String fileName() {
            return this.path.substring(this.path.lastIndexOf('/') + 1);
        }

        /** The namespace folder, such as {@code framedblocks} in {@code assets/framedblocks/textures/...}. */
        public String namespace() {
            String[] parts = this.path.split("/", 3);
            return parts.length < 2 ? "" : parts[1];
        }

        /** The path inside its category, such as {@code block/framed_slab.png} for a texture. */
        public String relativePath() {
            String prefix = this.category.root() + "/" + namespace() + "/" + this.category.folder() + "/";
            return this.path.startsWith(prefix) ? this.path.substring(prefix.length()) : fileName();
        }

        /** The folders between the category and the file, such as {@code block}; empty directly in the category. */
        public String folder() {
            String relative = relativePath();
            int separator = relative.lastIndexOf('/');
            return separator < 0 ? "" : relative.substring(0, separator);
        }

        /** The file name without its extension, used to match resources to registry ids. */
        public String stem() {
            String name = fileName();
            int dot = name.indexOf('.');
            return dot < 0 ? name : name.substring(0, dot);
        }

        public NavigationTarget target() {
            return this.archive
                    ? new NavigationTarget.ArchiveEntry(this.file, this.path)
                    : new NavigationTarget.LocalFile(this.file.resolve(this.path));
        }
    }

    private record Listing(long size, long modified, List<Resource> resources) {
    }

    private static final Map<Path, Listing> CACHE = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Path, Listing> eldest) {
            return size() > MAX_CACHED_FILES;
        }
    };

    private ModResources() {
    }

    /** Vanilla assets may be a separate archive from the transformed Minecraft classes. Blocking. */
    static List<Path> vanillaArchives(List<RuntimeInventory.Source> sources) {
        List<Path> archives = new ArrayList<>();
        for (RuntimeInventory.Source source : sources) {
            if (source.kind() != RuntimeInventory.SourceKind.ARCHIVE) continue;
            Path file = source.path();
            if (archives.contains(file) || !Files.isRegularFile(file)) continue;
            try (ZipFile zip = new ZipFile(file.toFile())) {
                if (zip.getEntry("assets/.mcassetsroot") != null || zip.getEntry("data/.mcassetsroot") != null) {
                    archives.add(file);
                }
            } catch (IOException unreadable) {
                // An unrelated unavailable library cannot supply vanilla assets.
            }
        }
        return List.copyOf(archives);
    }

    /** Lists the resources of every file, in file order. Blocking; not on the Swing thread. */
    public static List<Resource> list(List<Path> files) throws IOException {
        List<Resource> resources = new ArrayList<>();
        for (Path file : files) {
            resources.addAll(list(file));
        }
        return resources;
    }

    public static List<Resource> list(Path file) throws IOException {
        Path normalized = file.toAbsolutePath().normalize();
        BasicFileAttributes attributes = Files.readAttributes(normalized, BasicFileAttributes.class);
        long modified = attributes.lastModifiedTime().toMillis();
        synchronized (CACHE) {
            Listing cached = CACHE.get(normalized);
            if (cached != null && cached.size() == attributes.size() && cached.modified() == modified) {
                return cached.resources();
            }
        }
        List<Resource> resources = attributes.isDirectory() ? listDirectory(normalized) : listArchive(normalized);
        synchronized (CACHE) {
            CACHE.put(normalized, new Listing(attributes.size(), modified, resources));
        }
        return resources;
    }

    /** The categories present, assets before data, each ordered by folder name. */
    public static List<Category> categories(List<Resource> resources) {
        return resources.stream()
                .map(Resource::category)
                .distinct()
                .sorted(Comparator.comparing(Category::root).thenComparing(Category::folder))
                .toList();
    }

    private static List<Resource> listArchive(Path archive) throws IOException {
        List<Resource> resources = new ArrayList<>();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                Category category = category(entry.getName());
                if (category != null) {
                    resources.add(new Resource(archive, true, entry.getName(), category));
                }
            }
        }
        resources.sort(Comparator.comparing(Resource::path));
        return List.copyOf(resources);
    }

    private static List<Resource> listDirectory(Path directory) throws IOException {
        List<Resource> resources = new ArrayList<>();
        for (String root : List.of("assets", "data")) {
            Path rootDirectory = directory.resolve(root);
            if (!Files.isDirectory(rootDirectory)) continue;
            try (Stream<Path> files = Files.walk(rootDirectory)) {
                files.filter(Files::isRegularFile).forEach(path -> {
                    String relative = directory.relativize(path).toString().replace('\\', '/');
                    Category category = category(relative);
                    if (category != null) {
                        resources.add(new Resource(directory, false, relative, category));
                    }
                });
            }
        }
        resources.sort(Comparator.comparing(Resource::path));
        return List.copyOf(resources);
    }

    /** {@code assets/<ns>/<folder>/...} and {@code data/<ns>/<folder>/...}; a file directly in the namespace is its own category. */
    static Category category(String path) {
        String[] parts = path.split("/", 4);
        if (parts.length < 3 || !(parts[0].equals("assets") || parts[0].equals("data")) || parts[1].isEmpty()) {
            return null;
        }
        if (parts.length == 3) {
            String name = parts[2];
            int dot = name.indexOf('.');
            return new Category(parts[0], dot <= 0 ? name : name.substring(0, dot));
        }
        return parts[2].isEmpty() ? null : new Category(parts[0], parts[2]);
    }
}
