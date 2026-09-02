package com.github.minecraft_ta.totalDebugCompanion.bytecode;

import com.github.minecraft_ta.totaldebug.storage.RuntimeInventory;
import com.github.minecraft_ta.totaldebug.storage.CacheFiles;

import com.github.minecraft_ta.totaldebug.storage.RuntimeInventory.RuntimeModule;
import com.github.tth05.jindex.ClassIndex;
import com.github.tth05.jindex.IndexedClass;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipFile;

/** Reads the class-file view described by one persisted runtime profile. */
public final class RuntimeSnapshotBytecodeSource implements ClassBytecodeSource, AutoCloseable {
    public record Source(int sourceId, Path path, String logicalUri, RuntimeModule module) {
        public Source {
            if (sourceId < 0) {
                throw new IllegalArgumentException("sourceId must not be negative");
            }
            path = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
            if (Objects.requireNonNull(logicalUri, "logicalUri").isBlank()) {
                throw new IllegalArgumentException("logicalUri must not be blank");
            }
            Objects.requireNonNull(module, "module");
        }

    }

    public record ClassOrigin(String logicalSource, String resourceName, RuntimeModule module) {
        public ClassOrigin {
            if (Objects.requireNonNull(logicalSource, "logicalSource").isBlank()) {
                throw new IllegalArgumentException("logicalSource must not be blank");
            }
            if (Objects.requireNonNull(resourceName, "resourceName").isBlank()) {
                throw new IllegalArgumentException("resourceName must not be blank");
            }
            Objects.requireNonNull(module, "module");
        }
    }

    private final Path inventoryFile;
    private final String inventoryId;
    private volatile boolean closed;
    private final ClassIndex classIndex;
    private final Map<Integer, Source> sourcesById;

    public static RuntimeSnapshotBytecodeSource fromIndexedSources(List<Source> sources, ClassIndex classIndex) {
        return new RuntimeSnapshotBytecodeSource(sources, classIndex, null, null);
    }

    public static RuntimeSnapshotBytecodeSource fromRuntime(List<Source> sources, ClassIndex classIndex,
                                                            Path inventoryFile, String inventoryId) {
        return new RuntimeSnapshotBytecodeSource(sources, classIndex,
                Objects.requireNonNull(inventoryFile), Objects.requireNonNull(inventoryId));
    }

    private RuntimeSnapshotBytecodeSource(List<Source> sources, ClassIndex classIndex, Path inventoryFile, String inventoryId) {
        this.inventoryFile = inventoryFile;
        this.inventoryId = inventoryId;
        List<Source> requestedSources = List.copyOf(Objects.requireNonNull(sources, "sources"));
        if (requestedSources.isEmpty()) {
            throw new IllegalArgumentException("sources must not be empty");
        }
        this.classIndex = Objects.requireNonNull(classIndex, "classIndex");

        Map<Integer, Source> byId = new LinkedHashMap<>();
        for (Source source : requestedSources) {
            Path normalized = source.path();
            Source previous = byId.putIfAbsent(source.sourceId(), source);
            if (previous != null && !previous.equals(source)) {
                throw new IllegalArgumentException("Source id " + source.sourceId() + " maps to more than one path");
            }
            if (!Files.isDirectory(normalized) && !Files.isRegularFile(normalized)) {
                throw new IllegalArgumentException("Runtime source does not exist: " + normalized);
            }
        }
        this.sourcesById = Map.copyOf(byId);
    }

    @Override
    public synchronized boolean hasClass(String className) {
        ensureOpen();
        String internalName = normalizeClassName(className);
        IndexedClass indexedClass = findIndexedClass(internalName);
        if (indexedClass == null) {
            return false;
        }
        requireIndexedSource(internalName, indexedClass);
        return true;
    }

    @Override
    public byte[] findClassBytes(String className) throws IOException {
        if (this.inventoryFile == null) {
            synchronized (this) {
                ensureOpen();
                return readClassBytes(className);
            }
        }
        // Waiting for a writer must not hold the native-index lifetime lock.
        return CacheFiles.locked(this.inventoryFile.getParent(), () -> {
            synchronized (this) {
                ensureOpen();
                CacheFiles.requireIdentity(this.inventoryFile, "id", this.inventoryId);
                return readClassBytes(className);
            }
        });
    }

    private byte[] readClassBytes(String className) throws IOException {
        String internalName = normalizeClassName(className);
        String resourceName = internalName + ".class";
        IndexedClass indexedClass = findIndexedClass(internalName);
        if (indexedClass == null) {
            return null;
        }

        Source source = requireIndexedSource(internalName, indexedClass);
        byte[] bytes = "jrt:/".equals(source.logicalUri())
                ? readJdk(resourceName)
                : readSource(source.path(), resourceName);
        if (bytes == null) {
            throw new IOException("Runtime index maps " + internalName.replace('/', '.')
                    + " to " + source.logicalUri() + ", but " + resourceName + " is missing");
        }
        return bytes;
    }

    public synchronized ClassOrigin findClassOrigin(String className) {
        ensureOpen();
        String internalName = normalizeClassName(className);
        String resourceName = internalName + ".class";
        IndexedClass indexedClass = findIndexedClass(internalName);
        if (indexedClass == null) {
            return null;
        }
        Source source = requireIndexedSource(internalName, indexedClass);
        return new ClassOrigin(source.logicalUri(), resourceName, source.module());
    }

    /** Also validates cached decompilation results that do not need to read any class bytes. */
    public void requireCurrent() throws IOException {
        ensureOpen();
        if (this.inventoryFile != null) {
            CacheFiles.requireIdentity(this.inventoryFile, "id", this.inventoryId);
        }
    }

    @Override
    public synchronized void close() {
        this.closed = true;
    }

    private void ensureOpen() {
        if (this.closed) {
            throw new IllegalStateException("Runtime bytecode source is closed");
        }
    }

    private IndexedClass findIndexedClass(String internalName) {
        int separator = internalName.lastIndexOf('/');
        String packageName = separator < 0 ? "" : internalName.substring(0, separator);
        String simpleName = separator < 0 ? internalName : internalName.substring(separator + 1);
        return this.classIndex.findClass(packageName, simpleName);
    }

    private Source requireIndexedSource(String internalName, IndexedClass indexedClass) {
        int sourceId = indexedClass.getSourceId();
        Source source = this.sourcesById.get(sourceId);
        if (source == null) {
            throw new IllegalStateException("Runtime index maps " + internalName.replace('/', '.')
                    + " to unknown source id " + sourceId);
        }
        return source;
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
