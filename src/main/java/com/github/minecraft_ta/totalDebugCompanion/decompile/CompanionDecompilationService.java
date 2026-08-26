package com.github.minecraft_ta.totalDebugCompanion.decompile;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.RuntimeSnapshotBytecodeSource;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceQuery;
import com.github.minecraft_ta.totalDebugCompanion.bytecode.reference.ReferenceUsage;
import com.github.minecraft_ta.totalDebugCompanion.decompiler.DecompilationResult;
import com.github.minecraft_ta.totalDebugCompanion.decompiler.DecompilerDiagnostic;
import com.github.minecraft_ta.totalDebugCompanion.decompiler.JavaDecompiler;
import com.github.minecraft_ta.totalDebugCompanion.decompiler.VineflowerDecompiler;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

public final class CompanionDecompilationService implements AutoCloseable {
    private static final String DECOMPILER_FORMAT = "vineflower-1.12.0-selective-naming-line-maps-2";

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

    public CompletableFuture<Path> openClass(String binaryName, int targetType, String targetIdentifier) {
        ensureOpen();
        String normalizedName = requireBinaryName(binaryName);
        String identifier = Objects.requireNonNullElse(targetIdentifier, "");
        return open(normalizedName, source -> SourceFileNavigation.open(
                source,
                targetType,
                identifier,
                normalizedName
        ));
    }

    public CompletableFuture<Path> openClassAtLine(String binaryName, int displayedLine) {
        if (displayedLine < 1) {
            throw new IllegalArgumentException("Displayed source line must be positive");
        }
        String normalizedName = requireBinaryName(binaryName);
        return open(normalizedName, source -> SourceFileNavigation.openLine(source, displayedLine));
    }

    public CompletableFuture<Path> openUsage(ReferenceUsage usage, ReferenceQuery query) {
        Objects.requireNonNull(usage, "usage");
        Objects.requireNonNull(query, "query");
        String binaryName = requireBinaryName(usage.location().className());
        return open(binaryName, source -> SourceFileNavigation.openUsage(
                source,
                usage.location(),
                query,
                binaryName
        ));
    }

    private CompletableFuture<Path> open(
            String binaryName,
            Consumer<DecompiledSource> navigation
    ) {
        CompletableFuture<DecompiledSource> task = load(binaryName);
        task.whenComplete((source, failure) -> {
            if (this.closed) {
                return;
            }
            if (failure == null) {
                try {
                    navigation.accept(source);
                } catch (RuntimeException exception) {
                    showFailure(binaryName, exception);
                }
            } else {
                Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                        ? failure.getCause()
                        : failure;
                showFailure(binaryName, cause);
            }
        });
        return task.thenApply(DecompiledSource::path);
    }

    CompletableFuture<Path> decompile(String binaryName) {
        return load(binaryName).thenApply(DecompiledSource::path);
    }

    public CompletableFuture<DecompiledSource> load(String binaryName) {
        ensureOpen();
        String normalizedName = requireBinaryName(binaryName);
        Path cached = this.sourceStore.find(normalizedName);
        if (cached != null) {
            try {
                return CompletableFuture.completedFuture(readStoredSource(normalizedName, cached));
            } catch (IOException exception) {
                return CompletableFuture.failedFuture(exception);
            }
        }
        synchronized (this.inFlightRequests) {
            CompletableFuture<DecompiledSource> existing = this.inFlightRequests.get(normalizedName);
            if (existing != null && !existing.isDone()) {
                return existing;
            }
            cached = this.sourceStore.find(normalizedName);
            if (cached != null) {
                try {
                    return CompletableFuture.completedFuture(readStoredSource(normalizedName, cached));
                } catch (IOException exception) {
                    return CompletableFuture.failedFuture(exception);
                }
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

    private DecompiledSource decompileNow(String binaryName) throws IOException {
        DecompilationResult result = this.javaDecompiler.decompile(binaryName, this.bytecodeSource);
        for (DecompilerDiagnostic diagnostic : result.diagnostics()) {
            System.err.println("Vineflower " + diagnostic.severity().name().toLowerCase()
                    + " for " + binaryName + ": " + diagnostic.message());
        }
        if (!result.isComplete()) {
            throw new IOException("Vineflower produced partial source for " + binaryName);
        }
        if (this.closed) {
            throw new IOException("Decompilation service closed before source could be stored");
        }
        Path path = this.sourceStore.write(binaryName, result.source(), result.lineMap());
        return new DecompiledSource(
                path,
                binaryName,
                result.source(),
                result.lineMap(),
                this.bytecodeSource.findClassOrigin(binaryName)
        );
    }

    private DecompiledSource readStoredSource(String binaryName, Path path) throws IOException {
        return new DecompiledSource(
                path,
                binaryName,
                java.nio.file.Files.readString(path, StandardCharsets.UTF_8),
                this.sourceStore.readLineMap(binaryName),
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

    private static void showFailure(String binaryName, Throwable failure) {
        failure.printStackTrace(System.err);
        String detail = failure.getMessage();
        if (detail == null || detail.isBlank()) {
            detail = failure.getClass().getSimpleName();
        }
        String message = "Unable to open " + binaryName + ": " + detail;
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                null,
                message,
                "Decompilation failed",
                JOptionPane.ERROR_MESSAGE
        ));
    }

    @Override
    public void close() {
        this.closed = true;
        this.worker.shutdownNow();
    }

    private void ensureOpen() {
        if (this.closed) {
            throw new IllegalStateException("Decompilation service is closed");
        }
    }
}
