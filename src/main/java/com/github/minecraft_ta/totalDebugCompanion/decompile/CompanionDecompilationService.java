package com.github.minecraft_ta.totalDebugCompanion.decompile;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.RuntimeSnapshotBytecodeSource;
import com.github.minecraft_ta.totalDebugCompanion.decompiler.DecompilationResult;
import com.github.minecraft_ta.totalDebugCompanion.decompiler.DecompilerDiagnostic;
import com.github.minecraft_ta.totalDebugCompanion.decompiler.JavaDecompiler;
import com.github.minecraft_ta.totalDebugCompanion.decompiler.VineflowerDecompiler;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class CompanionDecompilationService implements AutoCloseable {
    private static final String DECOMPILER_FORMAT = "vineflower-1.12.0-selective-naming-debug-metadata-5";

    private final DecompiledSourceStore sourceStore;
    private final RuntimeSnapshotBytecodeSource bytecodeSource;
    private final JavaDecompiler javaDecompiler;
    private final ExecutorService worker;
    private final Map<String, CompletableFuture<DecompiledSource>> inFlightRequests = new HashMap<>();
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
    }

    CompletableFuture<Path> decompile(String binaryName) {
        return load(binaryName).thenApply(DecompiledSource::path);
    }

    public CompletableFuture<DecompiledSource> load(String binaryName) {
        ensureOpen();
        String normalizedName = requireBinaryName(binaryName);
        try {
            DecompiledSource cached = readStoredSource(normalizedName);
            if (cached != null) {
                return CompletableFuture.completedFuture(cached);
            }
        } catch (IOException exception) {
            return CompletableFuture.failedFuture(exception);
        }
        synchronized (this.inFlightRequests) {
            ensureOpen();
            CompletableFuture<DecompiledSource> existing = this.inFlightRequests.get(normalizedName);
            if (existing != null && !existing.isDone()) {
                return existing;
            }
            try {
                DecompiledSource cached = readStoredSource(normalizedName);
                if (cached != null) {
                    return CompletableFuture.completedFuture(cached);
                }
            } catch (IOException exception) {
                return CompletableFuture.failedFuture(exception);
            }

            CompletableFuture<DecompiledSource> task = CompletableFuture.supplyAsync(() -> {
                try {
                    return decompileNow(normalizedName);
                } catch (IOException exception) {
                    throw new CompletionException(exception);
                }
            }, this.worker);
            this.inFlightRequests.put(normalizedName, task);
            task.whenComplete((ignored, failure) -> {
                synchronized (this.inFlightRequests) {
                    this.inFlightRequests.remove(normalizedName, task);
                }
            });
            return task;
        }
    }

    public Path cacheDirectory() {
        return this.sourceStore.directory();
    }

    public java.util.List<String> cachedClasses() throws IOException {
        synchronized (this.inFlightRequests) {
            // A retired tree may finish refreshing after its runtime has been replaced.
            return this.closed ? java.util.List.of() : this.sourceStore.cachedClasses();
        }
    }

    public byte[] loadClassBytes(String binaryName) throws IOException {
        return this.bytecodeSource.findClassBytes(requireBinaryName(binaryName));
    }

    public RuntimeSnapshotBytecodeSource.ClassOrigin findClassOrigin(String binaryName) {
        return this.bytecodeSource.findClassOrigin(requireBinaryName(binaryName));
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
        synchronized (this.inFlightRequests) {
            ensureOpen();
            this.bytecodeSource.requireCurrent();
            Path path = this.sourceStore.write(
                    binaryName,
                    result.source(),
                    result.lineMap(),
                    result.variableNames()
            );
            return new DecompiledSource(
                    path,
                    binaryName,
                    result.source(),
                    result.lineMap(),
                    result.variableNames(),
                    this.bytecodeSource.findClassOrigin(binaryName)
            );
        }
    }

    private DecompiledSource readStoredSource(String binaryName) throws IOException {
        ensureOpen();
        var stored = this.sourceStore.read(binaryName);
        if (stored == null) {
            return null;
        }
        this.bytecodeSource.requireCurrent();
        var metadata = stored.debug();
        return new DecompiledSource(
                stored.path(),
                binaryName,
                stored.source(),
                metadata.lines(),
                metadata.names(),
                this.bytecodeSource.findClassOrigin(binaryName)
        );
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
        synchronized (this.inFlightRequests) {
            this.closed = true;
            for (var task : java.util.List.copyOf(this.inFlightRequests.values())) {
                task.cancel(false);
            }
            this.worker.shutdownNow();
        }
        this.bytecodeSource.close();
    }

    private void ensureOpen() {
        if (this.closed) {
            throw new IllegalStateException("Decompilation service is closed");
        }
    }
}
