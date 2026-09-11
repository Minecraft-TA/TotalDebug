package com.github.minecraft_ta.totalDebugCompanion.runtime;

import com.github.minecraft_ta.totaldebug.storage.RuntimeInventory;
import com.github.minecraft_ta.totaldebug.storage.InstancePaths;
import com.github.minecraft_ta.totaldebug.storage.AtomicFiles;
import com.github.minecraft_ta.totaldebug.storage.CacheFiles;
import com.github.minecraft_ta.totaldebug.storage.RuntimePhase;

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
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;
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
    private final Function<String, ClassIndex> indexLoader;
    private final CopyOnWriteArrayList<Consumer<Status>> listeners = new CopyOnWriteArrayList<>();
    private volatile Status status = new Status(Phase.WAITING, "Waiting for runtime inventory", null);
    private String activeInventoryId;
    private Path activeDataDirectory;
    private Work pending;
    private boolean closed;

    private static final class Work {
        final Path root;
        final Path inventoryFile;
        // Bound before loading; a matching live announcement can also bind a queued restore.
        String inventoryId;

        Work(Path root, Path inventoryFile, String inventoryId) {
            this.root = root;
            this.inventoryFile = inventoryFile;
            this.inventoryId = inventoryId;
        }
    }

    public RuntimeIndexService(Object lifecycleLock, Consumer<ReadySnapshot> readyHandler) {
        this(lifecycleLock, readyHandler, ClassIndex::fromFile);
    }

    RuntimeIndexService(Object lifecycleLock, Consumer<ReadySnapshot> readyHandler,
                        Function<String, ClassIndex> indexLoader) {
        this.lifecycleLock = Objects.requireNonNull(lifecycleLock, "lifecycleLock");
        this.readyHandler = Objects.requireNonNull(readyHandler, "readyHandler");
        this.indexLoader = Objects.requireNonNull(indexLoader, "indexLoader");
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
            ensureOpen();
            // Preparation can announce an unchanged inventory after an offline restore.
            // Keep that work until the live identity proves that it is different.
            if (this.pending == null) {
                update(new Status(Phase.WAITING, detail, null));
            }
        }
    }

    public void removeStatusListener(Consumer<Status> listener) {
        this.listeners.remove(listener);
    }

    public void restore(Path dataDirectory) {
        synchronized (this.lifecycleLock) {
            ensureOpen();
            this.activeInventoryId = null;
            this.activeDataDirectory = null;
            Path root = normalizeDataDirectory(dataDirectory);
            Path inventoryFile = new InstancePaths(root).inventory();
            if (this.pending != null && root.equals(this.pending.root)) {
                return;
            }
            this.pending = null;
            if (!Files.isRegularFile(inventoryFile)) {
                update(new Status(Phase.WAITING, "Waiting for runtime inventory", null));
                return;
            }
            update(new Status(Phase.LOADING, "Loading the previous runtime inventory", null));
            submit(new Work(root, inventoryFile, null));
        }
    }

    public void clear() {
        synchronized (this.lifecycleLock) {
            this.pending = null;
            this.activeInventoryId = null;
            this.activeDataDirectory = null;
        }
    }

    public void accept(Path dataDirectory, String expectedInventoryId, Path inventoryFile) {
        synchronized (this.lifecycleLock) {
            ensureOpen();
            Path root = normalizeDataDirectory(dataDirectory);
            String inventoryId = Objects.requireNonNull(expectedInventoryId, "expectedInventoryId");
            if (inventoryId.equals(this.activeInventoryId) && root.equals(this.activeDataDirectory)) {
                this.pending = null;
                update(new Status(Phase.READY, "Class index ready", null));
                return;
            }
            Path file = Objects.requireNonNull(inventoryFile, "inventoryFile").toAbsolutePath().normalize();
            if (this.pending != null && root.equals(this.pending.root) && file.equals(this.pending.inventoryFile)
                    && (this.pending.inventoryId == null || inventoryId.equals(this.pending.inventoryId))) {
                this.pending.inventoryId = inventoryId;
                return;
            }
            update(new Status(Phase.PREPARING, "Reading runtime inventory", null));
            submit(new Work(root, file, inventoryId));
        }
    }

    public void failedBeforeBuild(String detail) {
        synchronized (this.lifecycleLock) {
            this.pending = null;
            this.activeInventoryId = null;
            this.activeDataDirectory = null;
            update(new Status(Phase.FAILED, Objects.requireNonNullElse(detail, "Runtime inventory failed"), null));
        }
    }

    private void submit(Work work) {
        this.pending = work;
        this.worker.execute(() -> buildOrLoad(work));
    }

    private void buildOrLoad(Work work) {
        try (var phase = RuntimePhase.start("index.request")) {
            checkpoint(work);
            Path expectedFile = new InstancePaths(work.root).inventory();
            if (!expectedFile.equals(work.inventoryFile)) {
                throw new IOException("Runtime inventory must be published at " + expectedFile);
            }
            ReadySnapshot ready = CacheFiles.locked(expectedFile.getParent(), () -> {
                checkpoint(work);
                RuntimeInventory inventory = RuntimeInventory.read(work.inventoryFile);
                synchronized (this.lifecycleLock) {
                    checkpoint(work);
                    if (work.inventoryId != null && !inventory.id().equals(work.inventoryId)) {
                        throw new IOException("Runtime inventory identity mismatch: expected " + work.inventoryId
                                + ", got " + inventory.id());
                    }
                    work.inventoryId = inventory.id();
                }
                update(work, new Status(Phase.LOADING, "Loading class index", null));
                Path indexFile = new InstancePaths(work.root).index();
                ReadySnapshot cached = loadCachedSnapshot(indexFile, inventory.id());
                if (cached != null) {
                    return cached;
                }
                checkpoint(work);
                update(work, new Status(Phase.BUILDING, "Building class index", null));
                return buildSnapshot(work, inventory, indexFile);
            });
            publishReady(work, ready);
        } catch (CancellationException ignored) {
            // Closing or superseding work prevents the next expensive phase and publication.
        } catch (IOException | RuntimeException exception) {
            synchronized (this.lifecycleLock) {
                if (this.pending == work && !this.closed) {
                    String message = exception.getMessage();
                    update(new Status(
                        Phase.FAILED,
                        message == null || message.isBlank() ? "Class index preparation failed" : message,
                        exception
                    ));
                }
            }
        } finally {
            synchronized (this.lifecycleLock) {
                if (this.pending == work) {
                    this.pending = null;
                }
            }
        }
    }

    private ReadySnapshot buildSnapshot(
            Work work,
            RuntimeInventory inventory,
            Path indexFile
    ) throws IOException {
        AtomicFiles.cleanupAbandonedStaging(indexFile.getParent());
        checkpoint(work);
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
        checkpoint(work);
        try (var phase = RuntimePhase.start("index.jdk-inputs")) {
            addJdkClasses(jdkSourceId, indexSources);
        }
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

        checkpoint(work);
        ClassIndex built;
        try (var phase = RuntimePhase.start("index.native-build")) {
            built = ClassIndex.fromSources(indexSources);
        }
        try (ClassIndex index = built) {
            checkpoint(work);
            ClassIndex validated = IndexCache.write(indexFile, index,
                    new IndexCache.Manifest(inventory.id(), publishedSources), () -> checkpoint(work));
            return new ReadySnapshot(inventory.id(), IndexCache.FORMAT + ":" + inventory.id(),
                    indexFile, publishedSources, validated);
        } catch (CancellationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IOException("JIndex could not build the runtime class index", exception);
        }
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
                    checkInterrupted();
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
                        checkInterrupted();
                        String relativeName = module.relativize(classFile).toString().replace('\\', '/');
                        if (isIndexableClass(relativeName) && seen.add(relativeName)) {
                            sources.add(IndexSource.classFile(sourceId, Files.readAllBytes(classFile)));
                        }
                    }
                }
            }
        }
    }

    private ReadySnapshot loadSnapshot(Path indexFile, IndexCache.Manifest manifest) throws IOException {
        CacheFiles.requireIdentity(indexFile.getParent().resolve("inventory.json"), "id", manifest.inventoryId());
        IndexCache.requireSources(manifest);
        try (var phase = RuntimePhase.start("index.cache-load")) {
            return new ReadySnapshot(manifest.inventoryId(), IndexCache.FORMAT + ":" + manifest.inventoryId(),
                    indexFile, manifest.sources(), this.indexLoader.apply(indexFile.toString()));
        } catch (RuntimeException exception) {
            throw new IOException("Unable to load the runtime class index: " + indexFile, exception);
        }
    }

    private ReadySnapshot loadCachedSnapshot(Path indexFile, String inventoryId) {
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

    private void publishReady(Work work, ReadySnapshot snapshot) {
        boolean installed = false;
        try (var phase = RuntimePhase.start("index.install")) {
            synchronized (this.lifecycleLock) {
                checkpoint(work);
                this.readyHandler.accept(snapshot);
                this.activeInventoryId = snapshot.inventoryId();
                this.activeDataDirectory = work.root;
                installed = true;
                update(new Status(Phase.READY, "Class index ready", null));
            }
        } finally {
            if (!installed) {
                snapshot.close();
            }
        }
    }

    private void checkpoint(Work work) {
        synchronized (this.lifecycleLock) {
            if (this.closed || this.pending != work) {
                throw new CancellationException("Runtime index request was superseded or closed");
            }
        }
        checkInterrupted();
    }

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Runtime indexing was interrupted");
        }
    }

    private void update(Work work, Status status) {
        synchronized (this.lifecycleLock) {
            checkpoint(work);
            update(status);
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
            this.pending = null;
            this.worker.shutdownNow();
        }
    }
}
