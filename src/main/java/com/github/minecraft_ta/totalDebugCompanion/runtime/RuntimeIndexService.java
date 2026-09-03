package com.github.minecraft_ta.totalDebugCompanion.runtime;

import com.github.minecraft_ta.totaldebug.storage.RuntimeInventory;
import com.github.minecraft_ta.totaldebug.storage.InstancePaths;
import com.github.minecraft_ta.totaldebug.storage.AtomicFiles;
import com.github.minecraft_ta.totaldebug.storage.CacheFiles;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.RuntimeSnapshotBytecodeSource;
import com.github.tth05.jindex.ClassIndex;
import com.github.tth05.jindex.IndexSource;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.Stream;

public final class RuntimeIndexService implements AutoCloseable {
    public enum Phase {
        WAITING,
        PREPARING,
        BUILDING,
        LOADING,
        READY,
        FAILED
    }

    public record Status(Phase phase, String detail, Throwable failure) {
        public Status {
            Objects.requireNonNull(phase, "phase");
            detail = Objects.requireNonNullElse(detail, "");
        }

        public boolean active() {
            return phase == Phase.PREPARING
                    || phase == Phase.BUILDING
                    || phase == Phase.LOADING;
        }
    }

    public record ReadySnapshot(
            String inventoryId,
            String signature,
            Path indexFile,
            List<RuntimeSnapshotBytecodeSource.Source> sources,
            ClassIndex index
    ) implements AutoCloseable {
        public ReadySnapshot {
            inventoryId = Objects.requireNonNullElse(inventoryId, "");
            Objects.requireNonNull(signature, "signature");
            Objects.requireNonNull(indexFile, "indexFile");
            sources = List.copyOf(sources);
            Objects.requireNonNull(index, "index");
        }

        @Override
        public void close() {
            index.close();
        }
    }

    record PreparedInput(IndexSource indexSource, RuntimeSnapshotBytecodeSource.Source publishedSource) {
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> Thread.ofPlatform()
            .daemon()
            .name("Companion runtime index")
            .unstarted(task));
    // Shares the application's profile lock so installation and profile changes cannot invert locks.
    private final Object lifecycleLock;
    private final Consumer<ReadySnapshot> readyHandler;
    private final CopyOnWriteArrayList<Consumer<Status>> listeners = new CopyOnWriteArrayList<>();
    private volatile Status status = new Status(Phase.WAITING, "Waiting for runtime inventory", null);
    private String activeInventoryId;
    private Path activeDataDirectory;
    private long generation;
    private boolean closed;

    public RuntimeIndexService(Object lifecycleLock, Consumer<ReadySnapshot> readyHandler) {
        this.lifecycleLock = Objects.requireNonNull(lifecycleLock, "lifecycleLock");
        this.readyHandler = Objects.requireNonNull(readyHandler, "readyHandler");
    }

    public Status status() {
        return this.status;
    }

    public void addStatusListener(Consumer<Status> listener) {
        Consumer<Status> checked = Objects.requireNonNull(listener, "listener");
        this.listeners.add(checked);
        checked.accept(this.status);
    }

    public void waiting(String detail) {
        synchronized (this.lifecycleLock) {
            this.generation++;
            this.activeInventoryId = null;
            this.activeDataDirectory = null;
            update(new Status(Phase.WAITING, detail, null));
        }
    }

    public void restore(Path dataDirectory) {
        synchronized (this.lifecycleLock) {
            ensureOpen();
            this.activeInventoryId = null;
            this.activeDataDirectory = null;
            long requestedGeneration = ++this.generation;
            Path root = normalizeDataDirectory(dataDirectory);
            Path inventoryFile = new InstancePaths(root).inventory();
            if (!Files.isRegularFile(inventoryFile)) {
                update(new Status(Phase.WAITING, "Waiting for runtime inventory", null));
                return;
            }
            update(new Status(Phase.LOADING, "Loading the previous runtime inventory", null));
            this.worker.execute(() -> buildOrLoad(requestedGeneration, root, null, inventoryFile));
        }
    }

    public void accept(Path dataDirectory, String expectedInventoryId, Path inventoryFile) {
        synchronized (this.lifecycleLock) {
            ensureOpen();
            Path root = normalizeDataDirectory(dataDirectory);
            String inventoryId = Objects.requireNonNull(expectedInventoryId, "expectedInventoryId");
            if (inventoryId.equals(this.activeInventoryId) && root.equals(this.activeDataDirectory)) {
                update(new Status(Phase.READY, "Class index ready", null));
                return;
            }
            long requestedGeneration = ++this.generation;
            update(new Status(Phase.PREPARING, "Reading runtime inventory", null));
            this.worker.execute(() -> buildOrLoad(
                    requestedGeneration,
                    root,
                    inventoryId,
                    Objects.requireNonNull(inventoryFile, "inventoryFile").toAbsolutePath().normalize()
            ));
        }
    }

    public void failedBeforeBuild(String detail) {
        synchronized (this.lifecycleLock) {
            this.generation++;
            update(new Status(Phase.FAILED, Objects.requireNonNullElse(detail, "Runtime inventory failed"), null));
        }
    }

    private void buildOrLoad(long requestedGeneration, Path root, String expectedInventoryId, Path inventoryFile) {
        try {
            Path expectedFile = new InstancePaths(root).inventory();
            if (!expectedFile.equals(inventoryFile)) {
                throw new IOException("Runtime inventory must be published at " + expectedFile);
            }
            if (!isCurrent(requestedGeneration)) {
                return;
            }
            update(new Status(Phase.BUILDING, "Preparing class index", null));
            ReadySnapshot ready = CacheFiles.locked(expectedFile.getParent(), () -> {
                RuntimeInventory inventory = RuntimeInventory.read(inventoryFile);
                if (expectedInventoryId != null && !inventory.id().equals(expectedInventoryId)) {
                    throw new IOException("Runtime inventory identity mismatch: expected " + expectedInventoryId
                            + ", got " + inventory.id());
                }
                Path indexFile = new InstancePaths(root).index();
                ReadySnapshot cached = loadCachedSnapshot(indexFile, inventory.id());
                return cached != null ? cached : buildSnapshot(inventory, indexFile);
            });
            publishReady(requestedGeneration, root, ready);
        } catch (IOException | RuntimeException exception) {
            if (isCurrent(requestedGeneration)) {
                String message = exception.getMessage();
                update(new Status(
                        Phase.FAILED,
                        message == null || message.isBlank() ? "Class index preparation failed" : message,
                        exception
                ));
            }
        }
    }

    private ReadySnapshot buildSnapshot(
            RuntimeInventory inventory,
            Path indexFile
    ) throws IOException {
        AtomicFiles.cleanupAbandonedStaging(indexFile.getParent());
        List<PreparedInput> prepared = prepareInputs(inventory);
        List<IndexSource> indexSources = new ArrayList<>();
        var publishedSourcesById = new LinkedHashMap<Integer, RuntimeSnapshotBytecodeSource.Source>();
        for (PreparedInput input : prepared) {
            indexSources.add(input.indexSource());
            publishedSourcesById.putIfAbsent(
                    input.publishedSource().sourceId(),
                    input.publishedSource()
            );
        }
        List<RuntimeSnapshotBytecodeSource.Source> publishedSources = new ArrayList<>(publishedSourcesById.values());
        int jdkSourceId = publishedSources.stream()
                .mapToInt(RuntimeSnapshotBytecodeSource.Source::sourceId)
                .max()
                .orElse(-1) + 1;
        addJdkClasses(jdkSourceId, indexSources);
        publishedSources.add(new RuntimeSnapshotBytecodeSource.Source(
                jdkSourceId,
                Path.of(inventory.javaHome()),
                "jrt:/",
                new RuntimeInventory.RuntimeModule(
                        "java-runtime",
                        "Java Runtime",
                        RuntimeInventory.ModuleKind.JAVA_RUNTIME
                )
        ));
        if (indexSources.isEmpty()) {
            throw new IOException("Runtime inventory contains no indexable classes");
        }

        try (ClassIndex index = ClassIndex.fromSources(indexSources)) {
            IndexCache.write(indexFile, index, new IndexCache.Manifest(inventory.id(), publishedSources));
        } catch (RuntimeException exception) {
            throw new IOException("JIndex could not build the runtime class index", exception);
        }
        return loadSnapshot(indexFile, IndexCache.read(indexFile));
    }

    static List<PreparedInput> prepareInputs(RuntimeInventory inventory) throws IOException {
        List<PreparedInput> inputs = new ArrayList<>();
        int sourceId = 0;
        for (int sourceNumber = 0; sourceNumber < inventory.sources().size(); sourceNumber++) {
            RuntimeInventory.Source source = inventory.sources().get(sourceNumber);
            int currentId = sourceId++;
            if (source.kind() == RuntimeInventory.SourceKind.DIRECTORY) {
                List<Path> classFiles;
                try (Stream<Path> paths = Files.walk(source.path())) {
                    classFiles = paths.filter(Files::isRegularFile).sorted().toList();
                }
                boolean added = false;
                for (Path classFile : classFiles) {
                    String relativeName = source.path().relativize(classFile).toString().replace('\\', '/');
                    if (!isIndexableClass(relativeName)) {
                        continue;
                    }
                    inputs.add(new PreparedInput(
                            IndexSource.classFile(currentId, Files.readAllBytes(classFile)),
                            new RuntimeSnapshotBytecodeSource.Source(
                                    currentId,
                                    source.path(),
                                    source.logicalUri(),
                                    source.module()
                            )
                    ));
                    added = true;
                }
                if (!added) {
                    continue;
                }
            } else {
                inputs.add(new PreparedInput(
                        IndexSource.archive(currentId, source.path().toString()),
                        new RuntimeSnapshotBytecodeSource.Source(
                                currentId,
                                source.path(),
                                source.logicalUri(),
                                source.module()
                        )
                ));
            }
        }
        return List.copyOf(inputs);
    }

    private static void addJdkClasses(int sourceId, List<IndexSource> sources) throws IOException {
        Set<String> seen = new LinkedHashSet<>();
        var jrt = FileSystems.getFileSystem(URI.create("jrt:/"));
        Path modules = jrt.getPath("/modules");
        try (Stream<Path> modulePaths = Files.list(modules)) {
            for (Path module : modulePaths.sorted().toList()) {
                try (Stream<Path> classPaths = Files.walk(module)) {
                    for (Path classFile : classPaths.filter(Files::isRegularFile).sorted().toList()) {
                        String relativeName = module.relativize(classFile).toString().replace('\\', '/');
                        if (isIndexableClass(relativeName) && seen.add(relativeName)) {
                            sources.add(IndexSource.classFile(sourceId, Files.readAllBytes(classFile)));
                        }
                    }
                }
            }
        }
    }

    private static ReadySnapshot loadSnapshot(Path indexFile, IndexCache.Manifest manifest) throws IOException {
        CacheFiles.requireIdentity(indexFile.getParent().resolve("inventory.json"), "id", manifest.inventoryId());
        IndexCache.requireSources(manifest);
        try {
            return new ReadySnapshot(manifest.inventoryId(), IndexCache.FORMAT + ":" + manifest.inventoryId(),
                    indexFile, manifest.sources(), ClassIndex.fromFile(indexFile.toString()));
        } catch (RuntimeException exception) {
            throw new IOException("Unable to load the runtime class index: " + indexFile, exception);
        }
    }

    private static ReadySnapshot loadCachedSnapshot(Path indexFile, String inventoryId) {
        if (!Files.isRegularFile(indexFile)) {
            return null;
        }
        try {
            IndexCache.Manifest manifest = IndexCache.read(indexFile);
            if (!inventoryId.equals(manifest.inventoryId())) {
                return null;
            }
            return loadSnapshot(indexFile, manifest);
        } catch (IOException ignored) {
            // An unusable cache is rebuilt once from the validated inventory; rebuild failures still propagate.
            return null;
        }
    }

    private void publishReady(long requestedGeneration, Path root, ReadySnapshot snapshot) {
        synchronized (this.lifecycleLock) {
            if (this.closed || this.generation != requestedGeneration) {
                snapshot.close();
                return;
            }
            try {
                this.readyHandler.accept(snapshot);
            } catch (RuntimeException exception) {
                snapshot.close();
                throw exception;
            }
            this.activeInventoryId = snapshot.inventoryId();
            this.activeDataDirectory = root;
            update(new Status(Phase.READY, "Class index ready", null));
        }
    }

    private boolean isCurrent(long requestedGeneration) {
        synchronized (this.lifecycleLock) {
            return !this.closed && this.generation == requestedGeneration;
        }
    }

    private void update(Status replacement) {
        this.status = replacement;
        for (Consumer<Status> listener : this.listeners) {
            listener.accept(replacement);
        }
    }

    private void ensureOpen() {
        synchronized (this.lifecycleLock) {
            if (this.closed) {
                throw new IllegalStateException("Runtime index service is closed");
            }
        }
    }

    private static Path normalizeDataDirectory(Path dataDirectory) {
        return Objects.requireNonNull(dataDirectory, "dataDirectory").toAbsolutePath().normalize();
    }

    private static boolean isIndexableClass(String name) {
        return name.endsWith(".class")
                && !name.equals("module-info.class")
                && !name.endsWith("/module-info.class");
    }

    @Override
    public void close() {
        synchronized (this.lifecycleLock) {
            if (this.closed) {
                return;
            }
            this.closed = true;
            this.activeInventoryId = null;
            this.activeDataDirectory = null;
            this.generation++;
            this.worker.shutdownNow();
        }
    }
}
