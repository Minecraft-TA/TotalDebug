package com.github.minecraft_ta.totalDebugCompanion.runtime;

import com.github.minecraft_ta.totaldebug.storage.RuntimeInventory;
import com.github.minecraft_ta.totaldebug.storage.InstancePaths;
import com.github.minecraft_ta.totaldebug.storage.AtomicFiles;
import com.github.minecraft_ta.totaldebug.storage.CacheFiles;
import com.github.minecraft_ta.totaldebug.storage.RuntimePhase;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.RuntimeSnapshotBytecodeSource;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.LocalSourceGuard;
import com.github.tth05.jindex.ClassIndex;
import com.github.tth05.jindex.IndexSource;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipFile;
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
        EMPTY,
        FAILED
    }

    public record Metrics(long classes, long elapsedNanos, boolean rebuilt) {}

    public record Status(Phase phase, String detail, Throwable failure, IndexIdentity.Kind sourceKind, Metrics metrics) {
        public Status(Phase phase, String detail, Throwable failure) {
            this(phase, detail, failure, IndexIdentity.Kind.RUNTIME);
        }

        public Status(Phase phase, String detail, Throwable failure, IndexIdentity.Kind sourceKind) {
            this(phase, detail, failure, sourceKind, null);
        }

        public Status {
            Objects.requireNonNull(phase, "phase");
            Objects.requireNonNull(sourceKind, "sourceKind");
            detail = Objects.requireNonNullElse(detail, "");
        }

        public boolean active() {
            return phase == Phase.PREPARING
                    || phase == Phase.BUILDING
                    || phase == Phase.LOADING;
        }
    }

    public record ReadySnapshot(
            IndexIdentity identity,
            String signature,
            Path indexFile,
            List<RuntimeSnapshotBytecodeSource.Source> sources,
            ClassIndex index,
            LocalSourceGuard localGuard
    ) implements AutoCloseable {
        public ReadySnapshot {
            Objects.requireNonNull(identity);
            Objects.requireNonNull(signature, "signature");
            Objects.requireNonNull(indexFile, "indexFile");
            sources = List.copyOf(sources);
            Objects.requireNonNull(index, "index");
        }

        public ReadySnapshot(String inventoryId, String signature, Path indexFile,
                             List<RuntimeSnapshotBytecodeSource.Source> sources, ClassIndex index) {
            this(IndexIdentity.runtime(inventoryId), signature, indexFile, sources, index, null);
        }

        @Override public ClassIndex index() {
            if (localGuard != null) localGuard.requireValid();
            return index;
        }

        public String inventoryId() { return identity.kind() == IndexIdentity.Kind.RUNTIME ? identity.value() : null; }
        public boolean isRuntime() { return identity.kind() == IndexIdentity.Kind.RUNTIME; }

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
    private Metrics activeMetrics;
    private Path activeDataDirectory;
    private Work pending;
    private boolean closed;

    private static final class Work {
        final Path root;
        final Path inventoryFile;
        final Path gameDirectory;
        boolean local;
        boolean forceBuild;
        boolean rebuilt;
        long started;
        boolean allowLocalFallback;
        String detail = "";
        String sourceDetail = "";
        String runtimeFailure = "";
        LocalSourceGuard localGuard;
        List<RuntimeSnapshotBytecodeSource.Source> localSources = List.of();
        // Bound before loading; a matching live announcement can also bind a queued restore.
        String inventoryId;

        Work(Path root, Path inventoryFile, String inventoryId) {
            this(root, inventoryFile, inventoryId, null);
        }

        Work(Path root, Path inventoryFile, String inventoryId, Path gameDirectory) {
            this.root = root;
            this.inventoryFile = inventoryFile;
            this.inventoryId = inventoryId;
            this.gameDirectory = gameDirectory;
            this.allowLocalFallback = gameDirectory != null;
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
        restore(dataDirectory, null);
    }

    public void restore(Path dataDirectory, Path gameDirectory) {
        restore(dataDirectory, gameDirectory, false);
    }

    public void rebuild(Path dataDirectory, Path gameDirectory) {
        restore(dataDirectory, gameDirectory, true);
    }

    private void restore(Path dataDirectory, Path gameDirectory, boolean forceBuild) {
        synchronized (this.lifecycleLock) {
            ensureOpen();
            this.activeInventoryId = null;
            this.activeMetrics = null;
            this.activeDataDirectory = null;
            Path root = normalizeDataDirectory(dataDirectory);
            Path inventoryFile = new InstancePaths(root).inventory();
            if (this.pending != null && root.equals(this.pending.root) && this.status.active()) {
                return;
            }
            this.pending = null;
            if (gameDirectory == null && !Files.isRegularFile(inventoryFile)) {
                update(new Status(Phase.WAITING, "Waiting for runtime inventory", null));
                return;
            }
            update(new Status(Phase.LOADING, "Loading the previous runtime inventory", null));
            Work work = new Work(root, inventoryFile, null, gameDirectory);
            work.forceBuild = forceBuild;
            submit(work);
        }
    }

    public void clear() {
        synchronized (this.lifecycleLock) {
            this.pending = null;
            this.activeInventoryId = null;
            this.activeMetrics = null;
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
                update(new Status(Phase.READY, "Runtime index ready", null, IndexIdentity.Kind.RUNTIME, activeMetrics));
                return;
            }
            Path file = Objects.requireNonNull(inventoryFile, "inventoryFile").toAbsolutePath().normalize();
            if (this.pending != null && !this.pending.local && root.equals(this.pending.root) && file.equals(this.pending.inventoryFile)
                    && (this.pending.inventoryId == null || inventoryId.equals(this.pending.inventoryId))) {
                this.pending.inventoryId = inventoryId;
                this.pending.allowLocalFallback = false;
                return;
            }
            update(new Status(Phase.PREPARING, "Reading runtime inventory", null));
            submit(new Work(root, file, inventoryId));
        }
    }

    public void failedBeforeBuild(String detail) {
        synchronized (this.lifecycleLock) {
            String message = Objects.requireNonNullElse(detail, "Runtime inventory failed");
            if (this.pending != null && this.pending.allowLocalFallback) {
                this.pending.runtimeFailure = message + ". ";
                update(new Status(this.status.phase(), message + ". Offline index preparation continues", null, this.status.sourceKind()));
                return;
            }
            this.pending = null;
            this.activeInventoryId = null;
            this.activeDataDirectory = null;
            update(new Status(Phase.FAILED, message, null));
        }
    }

    private void submit(Work work) {
        this.pending = work;
        this.worker.execute(() -> buildOrLoad(work));
    }

    private void buildOrLoad(Work work) {
        work.started = System.nanoTime();
        try (var phase = RuntimePhase.start("index.request")) {
            checkpoint(work);
            Path expectedFile = new InstancePaths(work.root).inventory();
            if (!expectedFile.equals(work.inventoryFile)) {
                throw new IOException("Runtime inventory must be published at " + expectedFile);
            }
            try {
                if (!Files.isRegularFile(work.inventoryFile)) throw new NoSuchFileException(work.inventoryFile.toString());
                CacheFiles.locked(expectedFile.getParent(), () -> RuntimeInventory.read(work.inventoryFile));
            } catch (IOException failure) {
                synchronized (lifecycleLock) {
                    checkpoint(work);
                    if (work.gameDirectory == null || work.inventoryId != null) throw failure;
                    work.local = true;
                    if (Files.exists(work.inventoryFile)) work.detail = "Saved runtime unavailable: " + failure.getMessage() + ". ";
                }
                buildOrLoadLocal(work);
                return;
            }
            ReadySnapshot ready = CacheFiles.locked(expectedFile.getParent(), () -> {
                checkpoint(work);
                RuntimeInventory inventory = RuntimeInventory.read(work.inventoryFile);
                for (var source : inventory.sources()) {
                    checkpoint(work);
                    if (source.kind() == RuntimeInventory.SourceKind.ARCHIVE) {
                        try (var archive = new ZipFile(source.path().toFile())) { archive.size(); }
                    }
                }
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
                ReadySnapshot cached = work.forceBuild ? null : loadCachedSnapshot(indexFile, inventory.id());
                if (cached != null) {
                    return cached;
                }
                checkpoint(work);
                update(work, new Status(Phase.BUILDING, "Building class index", null));
                return buildSnapshot(work, prepareInputs(inventory), IndexIdentity.runtime(inventory.id()), indexFile, inventory.javaHome());
            });
            publishReady(work, ready);
        } catch (CancellationException ignored) {
            // Closing or superseding work prevents the next expensive phase and publication.
        } catch (IOException | RuntimeException exception) {
            Throwable reported = exception;
            boolean fallback;
            synchronized (lifecycleLock) {
                fallback = pending == work && !closed && work.allowLocalFallback && !work.local;
                if (fallback) {
                    work.local = true;
                    work.detail = "Saved runtime unavailable: " + exception.getMessage() + ". ";
                }
            }
            if (fallback) {
                try { buildOrLoadLocal(work); return; }
                catch (CancellationException ignored) { return; }
                catch (IOException | RuntimeException failure) { reported = failure; }
            }
            synchronized (this.lifecycleLock) {
                if (this.pending == work && !this.closed) {
                    String message = reported.getMessage();
                    update(work, new Status(
                        Phase.FAILED,
                        message == null || message.isBlank() ? "Class index preparation failed" : message,
                        reported
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

    private void buildOrLoadLocal(Work work) throws IOException {
        checkpoint(work);
        update(work, new Status(Phase.PREPARING, work.detail + "Reading local mod archives", null));
        var scan = LocalModSources.scan(work.gameDirectory, () -> checkpoint(work));
        work.localGuard = scan.guard();
        work.localSources = scan.sources();
        work.sourceDetail = scan.detail();
        if (work.localSources.isEmpty()) {
            update(work, new Status(Phase.EMPTY, work.detail + "No mod archives found", null));
            return;
        }
        if (scan.readable().isEmpty()) {
            update(work, new Status(Phase.EMPTY, work.detail + work.sourceDetail + "No readable mod archives", null));
            return;
        }
        IndexIdentity identity = scan.identity();
        Path indexFile = new InstancePaths(work.root).index();
        ReadySnapshot ready = CacheFiles.locked(indexFile.getParent(), () -> {
            checkpoint(work);
            if (!work.forceBuild && Files.isRegularFile(indexFile)) {
                try {
                    var manifest = IndexCache.read(indexFile);
                    if (identity.equals(manifest.identity())) {
                        var cached = loadSnapshot(indexFile, manifest, scan.guard());
                        work.sourceDetail = manifest.detail();
                        return cached;
                    }
                } catch (IOException ignored) { /* Rebuild the generated index from the current archives. */ }
            }
            update(work, new Status(Phase.BUILDING, work.detail + "Building local mod index", null));
            var prepared = LocalModSources.prepare(scan, () -> checkpoint(work));
            work.sourceDetail = prepared.detail();
            return buildSnapshot(work, prepared.inputs(), identity, indexFile, System.getProperty("java.home"));
        });
        publishReady(work, ready);
    }

    private ReadySnapshot buildSnapshot(
            Work work,
            List<PreparedInput> prepared,
            IndexIdentity identity,
            Path indexFile,
            String javaHome
    ) throws IOException {
        work.rebuilt = true;
        AtomicFiles.cleanupAbandonedStaging(indexFile.getParent());
        checkpoint(work);
        List<IndexSource> indexSources = new ArrayList<>();
        var publishedSourcesById = new LinkedHashMap<Integer, RuntimeSnapshotBytecodeSource.Source>();
        for (var source : work.localSources) {
            publishedSourcesById.put(source.sourceId(), source);
        }
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
                Path.of(javaHome),
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
                    new IndexCache.Manifest(identity, publishedSources, work.sourceDetail), () -> checkpoint(work));
            return readySnapshot(identity, indexFile, publishedSources, validated, work.localGuard);
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

    private ReadySnapshot loadSnapshot(Path indexFile, IndexCache.Manifest manifest, LocalSourceGuard guard) throws IOException {
        if (manifest.identity().kind() == IndexIdentity.Kind.RUNTIME)
            CacheFiles.requireIdentity(indexFile.getParent().resolve("inventory.json"), "id", manifest.inventoryId());
        if (guard == null) IndexCache.requireSources(manifest);
        else {
            IndexCache.requireSourcePaths(manifest);
            guard.checkAll();
        }
        try (var phase = RuntimePhase.start("index.cache-load")) {
            return readySnapshot(manifest.identity(), indexFile, manifest.sources(), this.indexLoader.apply(indexFile.toString()), guard);
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
            return loadSnapshot(indexFile, manifest, null);
        } catch (IOException ignored) {
            // An unusable cache is rebuilt once from the validated inventory; rebuild failures still propagate.
            return null;
        }
    }

    private static ReadySnapshot readySnapshot(IndexIdentity identity, Path file,
                                              List<RuntimeSnapshotBytecodeSource.Source> sources, ClassIndex index,
                                              LocalSourceGuard guard) throws IOException {
        try {
            if (guard != null) guard.checkAll();
            return new ReadySnapshot(identity, identity.signature(), file, sources, index, guard);
        } catch (IOException | RuntimeException failure) { index.close(); throw failure; }
    }

    private void publishReady(Work work, ReadySnapshot snapshot) {
        boolean installed = false;
        try (var phase = RuntimePhase.start("index.install")) {
            synchronized (this.lifecycleLock) {
                checkpoint(work);
                Metrics metrics = new Metrics(snapshot.index().getStatistics().classCount(), System.nanoTime() - work.started, work.rebuilt);
                this.readyHandler.accept(snapshot);
                this.activeInventoryId = snapshot.inventoryId();
                this.activeMetrics = metrics;
                this.activeDataDirectory = work.root;
                installed = true;
                update(work, new Status(Phase.READY, work.detail + work.sourceDetail + (snapshot.isRuntime() ? "Runtime index ready" : "Local index ready"), null, snapshot.identity().kind(), metrics));
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
            update(new Status(status.phase(), work.runtimeFailure + status.detail(), status.failure(),
                    work.local ? IndexIdentity.Kind.LOCAL : IndexIdentity.Kind.RUNTIME, status.metrics()));
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
