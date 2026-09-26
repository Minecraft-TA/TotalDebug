package com.github.minecraft_ta.totalDebugCompanion.itemrender;

import java.io.File;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class ResourcePackStack implements AutoCloseable {

    private static final int COPY_BUFFER_SIZE = 16 * 1024;

    private final List<ResourceRoot> roots;

    private ResourcePackStack(List<ResourceRoot> roots) {
        this.roots = roots;
    }

    static ResourcePackStack open(List<ItemRenderResourceRoot> rootDescriptors) throws IOException {
        Objects.requireNonNull(rootDescriptors, "rootDescriptors");
        if (rootDescriptors.isEmpty()) {
            throw new IllegalArgumentException("At least one resource root is required");
        }

        List<ResourceRoot> roots = new ArrayList<>(rootDescriptors.size());
        try {
            for (ItemRenderResourceRoot descriptor : rootDescriptors) {
                Objects.requireNonNull(descriptor, "resource root");
                Path absolute = descriptor.path();
                if (Files.isDirectory(absolute)) {
                    roots.add(new DirectoryRoot(descriptor));
                } else if (Files.isRegularFile(absolute)) {
                    roots.add(new ArchiveRoot(descriptor));
                } else {
                    throw new IOException("Resource root does not exist: " + absolute);
                }
            }
            return new ResourcePackStack(List.copyOf(roots));
        } catch (IOException | RuntimeException exception) {
            closeOpenedRoots(roots, exception);
            throw exception;
        }
    }

    byte[] readRequired(String resourcePath, int maximumBytes) throws IOException {
        return read(resourcePath, maximumBytes).orElseThrow(
                () -> new ItemRenderException(
                        ItemRenderException.Kind.MISSING_RESOURCE,
                        resourcePath,
                        "Missing resource " + resourcePath + " in the supplied resource roots"
                )
        );
    }

    List<String> listResources(String prefix, String suffix) throws IOException {
        validateResourcePath(prefix);
        Objects.requireNonNull(suffix, "suffix");
        Set<String> resources = new LinkedHashSet<>();
        for (ResourceRoot root : this.roots) {
            resources.addAll(root.list(prefix, suffix));
        }
        return resources.stream().sorted().toList();
    }

    Optional<byte[]> read(String resourcePath, int maximumBytes) throws IOException {
        validateResourcePath(resourcePath);
        if (maximumBytes < 1) {
            throw new IllegalArgumentException("maximumBytes must be positive");
        }

        for (int index = this.roots.size() - 1; index >= 0; index--) {
            Optional<byte[]> resource = this.roots.get(index).read(resourcePath, maximumBytes);
            if (resource.isPresent()) {
                return resource;
            }
        }
        return Optional.empty();
    }

    /** Reads additive resources in pack order, with a byte limit for the whole stack. */
    Optional<byte[]> readMetadata(String resourcePath, int maximumBytes) throws IOException {
        validateResourcePath(resourcePath);
        int source = -1;
        for (int index = roots.size() - 1; index >= 0; index--) {
            if (roots.get(index).contains(resourcePath)) { source = index; break; }
        }
        if (source < 0) return Optional.empty();
        for (int index = roots.size() - 1; index >= source; index--) {
            var metadata = roots.get(index).read(resourcePath + ".mcmeta", maximumBytes);
            if (metadata.isPresent()) return metadata;
        }
        return Optional.empty();
    }

    /** Reads additive resources in pack order, with a byte limit for the whole stack. */
    List<byte[]> readStack(String resourcePath, int maximumBytes) throws IOException {
        validateResourcePath(resourcePath);
        if (maximumBytes < 1) {
            throw new IllegalArgumentException("maximumBytes must be positive");
        }
        List<byte[]> result = new ArrayList<>();
        long total = 0;
        for (ResourceRoot root : this.roots) {
            Optional<byte[]> bytes = root.read(resourcePath, maximumBytes);
            if (bytes.isPresent()) {
                total += bytes.get().length;
                if (total > maximumBytes) {
                    throw resourceLimit(resourcePath + " resource stack", maximumBytes);
                }
                result.add(bytes.get());
            }
        }
        return List.copyOf(result);
    }

    boolean contains(String resourcePath) throws IOException {
        validateResourcePath(resourcePath);
        for (int index = this.roots.size() - 1; index >= 0; index--) {
            if (this.roots.get(index).contains(resourcePath)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        for (ResourceRoot root : this.roots) {
            try {
                root.close();
            } catch (IOException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static void validateResourcePath(String resourcePath) {
        Objects.requireNonNull(resourcePath, "resourcePath");
        if (resourcePath.isBlank() || resourcePath.startsWith("/") || resourcePath.startsWith("\\")) {
            throw new IllegalArgumentException("Invalid resource path: " + resourcePath);
        }
        for (String segment : resourcePath.replace('\\', '/').split("/")) {
            if (segment.isEmpty() || segment.equals("..")) {
                throw new IllegalArgumentException("Invalid resource path: " + resourcePath);
            }
        }
    }

    private static byte[] readBounded(InputStream input, long declaredSize, int maximumBytes, String description) throws IOException {
        if (declaredSize > maximumBytes) {
            throw resourceLimit(description, maximumBytes);
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream(
                declaredSize > 0 ? (int) Math.min(declaredSize, maximumBytes) : COPY_BUFFER_SIZE
        );
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            total += read;
            if (total > maximumBytes) {
                throw resourceLimit(description, maximumBytes);
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static ItemRenderException resourceLimit(String description, int maximumBytes) {
        return new ItemRenderException(
                ItemRenderException.Kind.RESOURCE_ERROR,
                "resource byte limit",
                description + " exceeds the " + maximumBytes + " byte limit"
        );
    }

    private static void closeOpenedRoots(List<ResourceRoot> roots, Exception original) {
        for (ResourceRoot root : roots) {
            try {
                root.close();
            } catch (IOException closeFailure) {
                original.addSuppressed(closeFailure);
            }
        }
    }

    private interface ResourceRoot extends AutoCloseable {

        boolean contains(String resourcePath) throws IOException;

        Optional<byte[]> read(String resourcePath, int maximumBytes) throws IOException;

        List<String> list(String prefix, String suffix) throws IOException;

        @Override
        default void close() throws IOException {
        }
    }

    private record DirectoryRoot(ItemRenderResourceRoot descriptor) implements ResourceRoot {

        @Override
        public boolean contains(String resourcePath) {
            Path packRoot = this.descriptor.path().resolve(this.descriptor.prefix()).normalize();
            Path resource = packRoot.resolve(resourcePath).normalize();
            return resource.startsWith(packRoot) && Files.isRegularFile(resource);
        }

        @Override
        public Optional<byte[]> read(String resourcePath, int maximumBytes) throws IOException {
            Path packRoot = this.descriptor.path().resolve(
                    this.descriptor.prefix().replace('/', File.separatorChar)
            ).normalize();
            Path resource = packRoot.resolve(resourcePath.replace('/', File.separatorChar)).normalize();
            if (!resource.startsWith(packRoot) || !Files.isRegularFile(resource)) {
                return Optional.empty();
            }
            long size = Files.size(resource);
            try (InputStream input = Files.newInputStream(resource)) {
                return Optional.of(readBounded(input, size, maximumBytes, resource.toString()));
            }
        }

        @Override
        public List<String> list(String prefix, String suffix) throws IOException {
            Path packRoot = this.descriptor.path().resolve(
                    this.descriptor.prefix().replace('/', File.separatorChar)
            ).normalize();
            Path start = packRoot.resolve(prefix.replace('/', File.separatorChar)).normalize();
            if (!start.startsWith(packRoot) || !Files.isDirectory(start)) {
                return List.of();
            }
            try (Stream<Path> files = Files.walk(start)) {
                return files
                        .filter(Files::isRegularFile)
                        .map(packRoot::relativize)
                        .map(Path::toString)
                        .map(path -> path.replace(File.separatorChar, '/'))
                        .filter(path -> path.endsWith(suffix))
                        .toList();
            }
        }
    }

    private static final class ArchiveRoot implements ResourceRoot {

        private final ItemRenderResourceRoot descriptor;
        private final ZipFile archive;

        private ArchiveRoot(ItemRenderResourceRoot descriptor) throws IOException {
            this.descriptor = descriptor;
            this.archive = new ZipFile(descriptor.path().toFile());
        }

        @Override
        public boolean contains(String resourcePath) {
            ZipEntry entry = this.archive.getEntry(this.descriptor.entryPath(resourcePath));
            return entry != null && !entry.isDirectory();
        }

        @Override
        public Optional<byte[]> read(String resourcePath, int maximumBytes) throws IOException {
            ZipEntry entry = this.archive.getEntry(this.descriptor.entryPath(resourcePath));
            if (entry == null || entry.isDirectory()) {
                return Optional.empty();
            }
            try (InputStream input = this.archive.getInputStream(entry)) {
                return Optional.of(readBounded(
                        input,
                        entry.getSize(),
                        maximumBytes,
                        this.descriptor.description()
                                + (this.descriptor.prefix().isEmpty() ? "!/" : "")
                                + resourcePath
                ));
            }
        }

        @Override
        public List<String> list(String prefix, String suffix) {
            return this.archive.stream()
                    .filter(entry -> !entry.isDirectory())
                    .map(ZipEntry::getName)
                    .filter(name -> name.startsWith(this.descriptor.entryPath(prefix)) && name.endsWith(suffix))
                    .map(name -> name.substring(this.descriptor.prefix().length()))
                    .toList();
        }

        @Override
        public void close() throws IOException {
            this.archive.close();
        }
    }
}
