package com.github.minecraft_ta.totalDebugCompanion.runtime;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.RuntimeSnapshotBytecodeSource;
import com.github.tth05.jindex.ClassIndex;
import com.github.tth05.jindex.IndexSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class RuntimeIndexService implements AutoCloseable {
    private static final String INDEX_DIRECTORY_NAME = "index";
    private static final String INDEX_FILE_NAME = "index";
    private static final String METADATA_FILE_NAME = "index.properties";
    private static final String CACHE_FORMAT = "1";

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

    private record PreparedInput(IndexSource indexSource, RuntimeSnapshotBytecodeSource.Source publishedSource) {
    }

    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> Thread.ofPlatform()
            .daemon()
            .name("Companion runtime index")
            .unstarted(task));
    private final Consumer<ReadySnapshot> readyHandler;
    private final CopyOnWriteArrayList<Consumer<Status>> listeners = new CopyOnWriteArrayList<>();
    private volatile Status status = new Status(Phase.WAITING, "Waiting for runtime inventory", null);
    private String activeInventoryId;
    private Path activeDataDirectory;
    private long generation;
    private boolean closed;

    public RuntimeIndexService(Consumer<ReadySnapshot> readyHandler) {
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

    public synchronized void waiting(String detail) {
        update(new Status(Phase.WAITING, detail, null));
    }

    public synchronized void restore(Path dataDirectory) {
        ensureOpen();
        this.activeInventoryId = null;
        this.activeDataDirectory = null;
        long requestedGeneration = ++this.generation;
        Path root = normalizeDataDirectory(dataDirectory);
        Path indexDirectory = root.resolve(INDEX_DIRECTORY_NAME);
        if (!hasCompleteIndex(indexDirectory)) {
            update(new Status(Phase.WAITING, "Waiting for runtime inventory", null));
            return;
        }
        update(new Status(Phase.LOADING, "Loading the previous class index", null));
        this.worker.execute(() -> {
            try {
                ReadySnapshot snapshot = loadSnapshot(indexDirectory);
                publishReady(requestedGeneration, root, snapshot);
            } catch (IOException | RuntimeException exception) {
                if (isCurrent(requestedGeneration)) {
                    update(new Status(Phase.WAITING, "Waiting for runtime inventory", null));
                }
            }
        });
    }

    public synchronized void accept(Path dataDirectory, String expectedInventoryId, Path inventoryFile) {
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

    public synchronized void failedBeforeBuild(String detail) {
        this.generation++;
        update(new Status(Phase.FAILED, Objects.requireNonNullElse(detail, "Runtime inventory failed"), null));
    }

    private void buildOrLoad(long requestedGeneration, Path root, String expectedInventoryId, Path inventoryFile) {
        try {
            RuntimeInventory inventory = RuntimeInventory.read(inventoryFile);
            if (!inventory.id().equals(expectedInventoryId)) {
                throw new IOException(
                        "Runtime inventory identity mismatch: expected " + expectedInventoryId + ", got " + inventory.id()
                );
            }
            if (!isCurrent(requestedGeneration)) {
                return;
            }
            Path indexDirectory = root.resolve(INDEX_DIRECTORY_NAME);
            ReadySnapshot snapshot;
            if (matchesIndex(indexDirectory, inventory.id())) {
                update(new Status(Phase.LOADING, "Loading cached class index", null));
                snapshot = loadSnapshot(indexDirectory);
            } else {
                update(new Status(Phase.BUILDING, "Building class index", null));
                snapshot = buildSnapshot(inventory, root, indexDirectory);
            }
            publishReady(requestedGeneration, root, snapshot);
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
            Path dataDirectory,
            Path indexDirectory
    ) throws IOException {
        Files.createDirectories(dataDirectory);
        Path staged = Files.createTempDirectory(dataDirectory, ".runtime-index-");
        try {
            List<PreparedInput> prepared = prepareInputs(inventory, staged, indexDirectory);
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
                    new RuntimeInventory.RuntimeModule("java-runtime", "Java Runtime")
            ));
            if (indexSources.isEmpty()) {
                throw new IOException("Runtime inventory contains no indexable classes");
            }

            Path stagedIndex = staged.resolve(INDEX_FILE_NAME);
            try (ClassIndex index = ClassIndex.fromSources(indexSources)) {
                index.saveToFile(stagedIndex.toString());
            } catch (RuntimeException exception) {
                throw new IOException("JIndex could not build the runtime class index", exception);
            }
            PreparedRuntimeSources.write(staged.resolve(PreparedRuntimeSources.FILE_NAME), List.copyOf(publishedSources));
            writeIndexMetadata(staged.resolve(METADATA_FILE_NAME), inventory.id());
            deleteTree(indexDirectory);
            try {
                Files.move(staged, indexDirectory, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                throw new IOException("The Companion data directory does not support atomic index updates", exception);
            }
            return loadSnapshot(indexDirectory);
        } finally {
            deleteTree(staged);
        }
    }

    private static List<PreparedInput> prepareInputs(
            RuntimeInventory inventory,
            Path staged,
            Path publishedDirectory
    ) throws IOException {
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
                sourceId = extractNestedArchives(
                        source.path(),
                        source.logicalUri(),
                        source.module(),
                        staged.resolve("nested").resolve("source-" + sourceNumber),
                        publishedDirectory.resolve("nested").resolve("source-" + sourceNumber),
                        sourceId,
                        inputs
                );
            }
        }
        return List.copyOf(inputs);
    }

    private static int extractNestedArchives(
            Path archive,
            String logicalArchive,
            RuntimeInventory.RuntimeModule runtimeModule,
            Path stagedDirectory,
            Path publishedDirectory,
            int nextSourceId,
            List<PreparedInput> inputs
    ) throws IOException {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            List<? extends ZipEntry> entries = zip.stream()
                    .filter(entry -> !entry.isDirectory() && entry.getName().endsWith(".jar"))
                    .sorted(Comparator.comparing(ZipEntry::getName))
                    .toList();
            for (int nestedNumber = 0; nestedNumber < entries.size(); nestedNumber++) {
                ZipEntry entry = entries.get(nestedNumber);
                Path stagedJar = stagedDirectory.resolve("nested-" + nestedNumber + ".jar");
                Path publishedJar = publishedDirectory.resolve("nested-" + nestedNumber + ".jar");
                Files.createDirectories(stagedJar.getParent());
                try (InputStream input = zip.getInputStream(entry)) {
                    Files.copy(input, stagedJar);
                }
                int sourceId = nextSourceId++;
                String logicalSource = logicalArchive + "!/" + entry.getName();
                inputs.add(new PreparedInput(
                        IndexSource.archive(sourceId, stagedJar.toString()),
                        new RuntimeSnapshotBytecodeSource.Source(
                                sourceId,
                                publishedJar,
                                logicalSource,
                                runtimeModule
                        )
                ));
                nextSourceId = extractNestedArchives(
                        stagedJar,
                        logicalSource,
                        runtimeModule,
                        stagedDirectory.resolve("nested-" + nestedNumber),
                        publishedDirectory.resolve("nested-" + nestedNumber),
                        nextSourceId,
                        inputs
                );
            }
        }
        return nextSourceId;
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

    private static ReadySnapshot openSnapshot(
            String inventoryId,
            Path indexFile,
            Path sourceFile
    ) throws IOException {
        List<RuntimeSnapshotBytecodeSource.Source> sources = PreparedRuntimeSources.read(sourceFile);
        try {
            return new ReadySnapshot(
                    inventoryId,
                    CACHE_FORMAT + ":" + inventoryId,
                    indexFile,
                    sources,
                    ClassIndex.fromFile(indexFile.toString())
            );
        } catch (RuntimeException exception) {
            throw new IOException("Unable to load the runtime class index", exception);
        }
    }

    private static ReadySnapshot loadSnapshot(Path indexDirectory) throws IOException {
        String inventoryId = readIndexInventoryId(indexDirectory);
        return openSnapshot(
                inventoryId,
                indexDirectory.resolve(INDEX_FILE_NAME),
                indexDirectory.resolve(PreparedRuntimeSources.FILE_NAME)
        );
    }

    private static boolean hasCompleteIndex(Path indexDirectory) {
        return Files.isRegularFile(indexDirectory.resolve(INDEX_FILE_NAME))
                && Files.isRegularFile(indexDirectory.resolve(PreparedRuntimeSources.FILE_NAME))
                && Files.isRegularFile(indexDirectory.resolve(METADATA_FILE_NAME));
    }

    private static boolean matchesIndex(Path indexDirectory, String inventoryId) {
        if (!hasCompleteIndex(indexDirectory)) {
            return false;
        }
        try {
            return inventoryId.equals(readIndexInventoryId(indexDirectory));
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    private static String readIndexInventoryId(Path indexDirectory) throws IOException {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(indexDirectory.resolve(METADATA_FILE_NAME))) {
            properties.load(input);
        }
        if (!CACHE_FORMAT.equals(required(properties, "format"))) {
            throw new IOException("Unsupported runtime index format");
        }
        return required(properties, "inventory.id");
    }

    private static void writeIndexMetadata(Path file, String inventoryId) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("format", CACHE_FORMAT);
        properties.setProperty("inventory.id", inventoryId);
        try (OutputStream output = Files.newOutputStream(file)) {
            properties.store(output, "TotalDebug Companion runtime index");
        }
    }

    private synchronized void publishReady(long requestedGeneration, Path root, ReadySnapshot snapshot) {
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

    private synchronized boolean isCurrent(long requestedGeneration) {
        return !this.closed && this.generation == requestedGeneration;
    }

    private void update(Status replacement) {
        this.status = replacement;
        for (Consumer<Status> listener : this.listeners) {
            listener.accept(replacement);
        }
    }

    private synchronized void ensureOpen() {
        if (this.closed) {
            throw new IllegalStateException("Runtime index service is closed");
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

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing active runtime field " + key);
        }
        return value;
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Override
    public synchronized void close() {
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
