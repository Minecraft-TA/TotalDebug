package com.github.minecraft_ta.totalDebugCompanion.decompile;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.RuntimeSnapshotBytecodeSource;
import com.github.minecraft_ta.totalDebugCompanion.decompiler.DecompilationResult;
import com.github.minecraft_ta.totalDebugCompanion.decompiler.DecompilerDiagnostic;
import com.github.minecraft_ta.totalDebugCompanion.decompiler.JavaDecompiler;
import com.github.minecraft_ta.totalDebugCompanion.decompiler.VineflowerDecompiler;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine;
import com.github.minecraft_ta.totalDebugCompanion.source.SourceDocument;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class CompanionDecompilationService implements AutoCloseable {
    private static final String DECOMPILER_FORMAT = "vineflower-1.12.0-source-symbols-8";

    private final DecompiledSourceStore sourceStore;
    private final RuntimeSnapshotBytecodeSource bytecodeSource;
    private final JavaDecompiler javaDecompiler;
    private final ExecutorService worker;
    private final ExecutorService cacheReaders;
    private final Map<String, CompletableFuture<DecompiledSource>> inFlightRequests = new HashMap<>();
    private final Object publicationLock = new Object();
    private volatile boolean closed;

    public CompanionDecompilationService(
            String runtimeSignature,
            Path dataDirectory,
            RuntimeSnapshotBytecodeSource bytecodeSource
    ) throws IOException {
        this(runtimeSignature, dataDirectory, bytecodeSource, new VineflowerDecompiler());
    }

    CompanionDecompilationService(
            String runtimeSignature,
            Path dataDirectory,
            RuntimeSnapshotBytecodeSource bytecodeSource,
            JavaDecompiler javaDecompiler
    ) throws IOException {
        this.sourceStore = DecompiledSourceStore.open(
                dataDirectory,
                requireNonBlank(runtimeSignature, "runtimeSignature"),
                DECOMPILER_FORMAT
        );
        this.bytecodeSource = Objects.requireNonNull(bytecodeSource, "bytecodeSource");
        this.javaDecompiler = Objects.requireNonNull(javaDecompiler, "javaDecompiler");
        this.worker = Executors.newSingleThreadExecutor(task -> Thread.ofPlatform()
                .daemon()
                .name("Companion decompiler")
                .unstarted(task));
        this.cacheReaders = Executors.newVirtualThreadPerTaskExecutor();
    }

    CompletableFuture<Path> decompile(String binaryName) {
        return load(binaryName).thenApply(DecompiledSource::path);
    }

    public CompletableFuture<DecompiledSource> load(String binaryName) {
        ensureOpen();
        String normalizedName = requireBinaryName(binaryName);
        synchronized (this.inFlightRequests) {
            ensureOpen();
            CompletableFuture<DecompiledSource> existing = this.inFlightRequests.get(normalizedName);
            if (existing != null && !existing.isDone()) {
                return existing;
            }
            var task = new CompletableFuture<DecompiledSource>();
            this.inFlightRequests.put(normalizedName, task);
            CompletableFuture.supplyAsync(() -> {
                try {
                    if (task.isCancelled()) throw new CancellationException();
                    ensureOpen();
                    return readStoredSource(normalizedName);
                } catch (IOException exception) {
                    throw new CompletionException(exception);
                }
            }, this.cacheReaders).thenCompose(cached -> {
                if (cached != null) return CompletableFuture.completedFuture(cached);
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        if (task.isCancelled()) throw new CancellationException();
                        ensureOpen();
                        return decompileNow(normalizedName);
                    }
                    catch (IOException exception) { throw new CompletionException(exception); }
                }, this.worker);
            }).whenComplete((source, failure) -> {
                synchronized (this.inFlightRequests) {
                    if (closed) task.cancel(false);
                    else if (failure != null) task.completeExceptionally(failure);
                    else task.complete(source);
                    this.inFlightRequests.remove(normalizedName, task);
                }
            });
            return task;
        }
    }

    public Path cacheDirectory() {
        return this.sourceStore.directory();
    }

    public List<String> cachedClasses() throws IOException {
        // A retired tree may finish refreshing after its runtime has been replaced.
        return this.closed ? List.of() : this.sourceStore.cachedClasses();
    }

    public DebugEngine.Source loadDebugSource(String binaryName) throws IOException {
        String normalizedName = requireBinaryName(binaryName);
        if (!this.bytecodeSource.hasClass(normalizedName)) {
            return null;
        }
        try {
            return load(normalizedName).join().debugSource();
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof IOException ioException) {
                throw ioException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw exception;
        }
    }

    private DecompiledSource decompileNow(String binaryName) throws IOException {
        DecompilationResult result = this.javaDecompiler.decompile(binaryName, this.bytecodeSource);
        for (DecompilerDiagnostic diagnostic : result.diagnostics()) {
            System.err.println("Vineflower " + diagnostic.severity().name().toLowerCase()
                    + " for " + binaryName + ": " + diagnostic.message());
        }
        if (!result.isComplete()) {
            throw new IOException("Vineflower produced partial source for " + binaryName);
        }
        this.bytecodeSource.requireCurrent();
        synchronized (this.publicationLock) {
            ensureOpen();
            SourceDocument document = new SourceDocument(binaryName, result.source(), result.lineMap(), result.variableNames(), result.symbols());
            Path path = this.sourceStore.write(document);
            return new DecompiledSource(path, document, this.bytecodeSource.findClassOrigin(binaryName));
        }
    }

    private DecompiledSource readStoredSource(String binaryName) throws IOException {
        ensureOpen();
        var stored = this.sourceStore.read(binaryName);
        if (stored == null) {
            return null;
        }
        this.bytecodeSource.requireCurrent();
        return new DecompiledSource(stored.path(), stored.document(), this.bytecodeSource.findClassOrigin(binaryName));
    }

    private static String requireBinaryName(String binaryName) {
        String name = requireNonBlank(binaryName, "binaryName");
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.endsWith(".class")) {
            throw new IllegalArgumentException("Expected a Java binary class name: " + name);
        }
        return name;
    }

    private static String requireNonBlank(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    @Override
    public void close() {
        synchronized (this.publicationLock) { this.closed = true; }
        synchronized (this.inFlightRequests) {
            for (var task : List.copyOf(this.inFlightRequests.values())) {
                task.cancel(false);
            }
            this.worker.shutdownNow();
            this.cacheReaders.shutdownNow();
        }
        this.bytecodeSource.close();
    }

    private void ensureOpen() {
        if (this.closed) {
            throw new IllegalStateException("Decompilation service is closed");
        }
    }
}
