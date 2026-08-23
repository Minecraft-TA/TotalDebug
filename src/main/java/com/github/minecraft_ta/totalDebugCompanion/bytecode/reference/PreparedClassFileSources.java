package com.github.minecraft_ta.totalDebugCompanion.bytecode.reference;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

final class PreparedClassFileSources implements AutoCloseable {
    private final List<ClassFileSource> sources;

    private PreparedClassFileSources(List<ClassFileSource> sources) {
        this.sources = sources;
    }

    static PreparedClassFileSources prepare(Collection<Path> sourcePaths) throws IOException {
        Objects.requireNonNull(sourcePaths, "sourcePaths");
        List<ClassFileSource> sources = new ArrayList<>();
        for (Path sourcePath : sourcePaths) {
            Objects.requireNonNull(sourcePath, "sourcePaths contains null");
            if (sourcePath.getFileSystem() != FileSystems.getDefault()) {
                throw new IOException("Reference-search source is not on the default filesystem: " + sourcePath);
            }
            if (!Files.exists(sourcePath)) {
                throw new IOException("Reference-search source does not exist: " + sourcePath);
            }
            if (Files.isDirectory(sourcePath)) {
                sources.add(new DirectorySource(sourcePath));
                continue;
            }
            if (!Files.isRegularFile(sourcePath)) {
                throw new IOException("Reference-search source is not a directory or archive: " + sourcePath);
            }
            sources.add(new ArchiveSource(sourcePath));
        }
        return new PreparedClassFileSources(List.copyOf(sources));
    }

    int classFileCount() throws IOException {
        long count = 0;
        for (ClassFileSource source : this.sources) {
            count += source.classFileCount();
            if (count > Integer.MAX_VALUE) {
                throw new IOException("Reference search found more than " + Integer.MAX_VALUE + " class files");
            }
        }
        return (int) count;
    }

    void read(ClassFileConsumer consumer) throws IOException {
        for (ClassFileSource source : this.sources) {
            if (!source.read(consumer)) {
                return;
            }
        }
    }

    @Override
    public void close() {
    }

    @FunctionalInterface
    interface ClassFileConsumer {
        boolean accept(String origin, byte[] bytes) throws IOException;
    }

    private interface ClassFileSource {
        int classFileCount() throws IOException;

        boolean read(ClassFileConsumer consumer) throws IOException;
    }

    private record DirectorySource(Path directory) implements ClassFileSource {
        @Override
        public int classFileCount() throws IOException {
            try (Stream<Path> paths = Files.walk(this.directory)) {
                return Math.toIntExact(paths.filter(this::isClassFile).count());
            }
        }

        @Override
        public boolean read(ClassFileConsumer consumer) throws IOException {
            List<Path> classFiles;
            try (Stream<Path> paths = Files.walk(this.directory)) {
                classFiles = paths.filter(this::isClassFile).sorted().toList();
            }
            for (Path classFile : classFiles) {
                if (!consumer.accept(classFile.toUri().toString(), Files.readAllBytes(classFile))) {
                    return false;
                }
            }
            return true;
        }

        private boolean isClassFile(Path path) {
            if (!Files.isRegularFile(path)) {
                return false;
            }
            String entryName = this.directory.relativize(path).toString().replace('\\', '/');
            return isSearchableClassEntry(entryName) && !entryName.startsWith("META-INF/versions/");
        }
    }

    private record ArchiveSource(Path archivePath) implements ClassFileSource {
        @Override
        public int classFileCount() throws IOException {
            try (JarFile archive = openArchive()) {
                return Math.toIntExact(searchableEntries(archive).size());
            } catch (IOException exception) {
                throw new IOException("Unable to read reference-search archive " + this.archivePath.toUri(), exception);
            }
        }

        @Override
        public boolean read(ClassFileConsumer consumer) throws IOException {
            try (JarFile archive = openArchive()) {
                for (JarEntry entry : searchableEntries(archive)) {
                    byte[] bytes;
                    try (InputStream input = archive.getInputStream(entry)) {
                        bytes = input.readAllBytes();
                    }
                    String origin = this.archivePath.toUri() + "!/" + entry.getName();
                    if (!consumer.accept(origin, bytes)) {
                        return false;
                    }
                }
                return true;
            } catch (IOException exception) {
                throw new IOException("Unable to read reference-search archive " + this.archivePath.toUri(), exception);
            }
        }

        private JarFile openArchive() throws IOException {
            return new JarFile(
                    this.archivePath.toFile(),
                    false,
                    java.util.zip.ZipFile.OPEN_READ,
                    Runtime.version()
            );
        }

        private static List<JarEntry> searchableEntries(JarFile archive) {
            return archive.versionedStream()
                    .filter(entry -> !entry.isDirectory() && isSearchableClassEntry(entry.getName()))
                    .sorted(Comparator.comparing(JarEntry::getName))
                    .toList();
        }
    }

    private static boolean isSearchableClassEntry(String entryName) {
        return entryName.endsWith(".class")
                && !entryName.equals("module-info.class")
                && !entryName.endsWith("/module-info.class");
    }

}
