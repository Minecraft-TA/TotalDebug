package com.github.minecraft_ta.totalDebugCompanion.bytecode;

import com.github.tth05.jindex.ClassIndex;
import com.github.tth05.jindex.IndexedClass;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipFile;

/** Reads the class-file view described by one persisted runtime profile. */
public final class RuntimeSnapshotBytecodeSource implements ClassBytecodeSource {
    private final List<Path> sources;
    private final List<Path> archiveSources;
    private final List<Path> directorySources;
    private final ClassIndex classIndex;

    public RuntimeSnapshotBytecodeSource(List<Path> sources, ClassIndex classIndex) {
        List<Path> requestedSources = List.copyOf(Objects.requireNonNull(sources, "sources"));
        if (requestedSources.isEmpty()) {
            throw new IllegalArgumentException("sources must not be empty");
        }
        this.classIndex = Objects.requireNonNull(classIndex, "classIndex");

        List<Path> normalizedSources = new ArrayList<>();
        List<Path> archives = new ArrayList<>();
        List<Path> directories = new ArrayList<>();
        for (Path source : requestedSources) {
            Path normalized = Objects.requireNonNull(source, "sources contains null")
                    .toAbsolutePath()
                    .normalize();
            normalizedSources.add(normalized);
            if (Files.isDirectory(normalized)) {
                directories.add(normalized);
            } else if (Files.isRegularFile(normalized)) {
                archives.add(normalized);
            } else {
                throw new IllegalArgumentException("Runtime source does not exist: " + normalized);
            }
        }
        this.sources = List.copyOf(normalizedSources);
        this.archiveSources = List.copyOf(archives);
        this.directorySources = List.copyOf(directories);
    }

    @Override
    public byte[] findClassBytes(String className) throws IOException {
        String internalName = normalizeClassName(className);
        String resourceName = internalName + ".class";
        IndexedClass indexedClass = findIndexedClass(internalName);
        Path preferredSource = null;

        if (indexedClass != null) {
            int sourceId = indexedClass.getSourceId();
            if (sourceId < this.archiveSources.size()) {
                preferredSource = this.archiveSources.get(sourceId);
                byte[] bytes = readSource(preferredSource, resourceName);
                if (bytes != null) {
                    return bytes;
                }
            } else {
                byte[] bytes = readDirectories(resourceName);
                if (bytes != null) {
                    return bytes;
                }
                bytes = readJdk(resourceName);
                if (bytes != null) {
                    return bytes;
                }
            }
        }

        for (Path source : this.sources) {
            if (source.equals(preferredSource)) {
                continue;
            }
            byte[] bytes = readSource(source, resourceName);
            if (bytes != null) {
                return bytes;
            }
        }
        return readJdk(resourceName);
    }

    private IndexedClass findIndexedClass(String internalName) {
        int separator = internalName.lastIndexOf('/');
        String packageName = separator < 0 ? "" : internalName.substring(0, separator);
        String simpleName = separator < 0 ? internalName : internalName.substring(separator + 1);
        return this.classIndex.findClass(packageName, simpleName);
    }

    private byte[] readDirectories(String resourceName) throws IOException {
        for (Path directory : this.directorySources) {
            byte[] bytes = readDirectory(directory, resourceName);
            if (bytes != null) {
                return bytes;
            }
        }
        return null;
    }

    private static byte[] readSource(Path source, String resourceName) throws IOException {
        return Files.isDirectory(source)
                ? readDirectory(source, resourceName)
                : readArchive(source, resourceName);
    }

    private static byte[] readDirectory(Path directory, String resourceName) throws IOException {
        Path classFile = directory.resolve(resourceName.replace('/', java.io.File.separatorChar)).normalize();
        if (!classFile.startsWith(directory) || !Files.isRegularFile(classFile)) {
            return null;
        }
        return Files.readAllBytes(classFile);
    }

    private static byte[] readArchive(Path archive, String resourceName) throws IOException {
        try (JarFile jar = new JarFile(archive.toFile(), false, ZipFile.OPEN_READ, Runtime.version())) {
            JarEntry entry = jar.getJarEntry(resourceName);
            if (entry == null || entry.isDirectory()) {
                return null;
            }
            try (InputStream input = jar.getInputStream(entry)) {
                return input.readAllBytes();
            }
        } catch (IOException exception) {
            throw new IOException("Unable to read runtime archive " + archive, exception);
        }
    }

    private static byte[] readJdk(String resourceName) throws IOException {
        try (InputStream input = ClassLoader.getPlatformClassLoader().getResourceAsStream(resourceName)) {
            return input == null ? null : input.readAllBytes();
        }
    }

    static String normalizeClassName(String className) {
        Objects.requireNonNull(className, "className");
        String normalized = className;
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.endsWith(".class")) {
            normalized = normalized.substring(0, normalized.length() - ".class".length());
        }
        if (normalized.indexOf('/') < 0) {
            normalized = normalized.replace('.', '/');
        }
        if (normalized.isBlank()
                || normalized.indexOf('\\') >= 0
                || normalized.startsWith("/")
                || normalized.endsWith("/")
                || normalized.contains("//")) {
            throw new IllegalArgumentException("Invalid class name: " + className);
        }
        for (String segment : normalized.split("/")) {
            if (segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("Invalid class name: " + className);
            }
        }
        return normalized;
    }
}
