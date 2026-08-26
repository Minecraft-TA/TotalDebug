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
import java.util.function.BiConsumer;

public final class CompanionDecompilationService implements AutoCloseable {
    private static final String DECOMPILER_FORMAT = "vineflower-1.12.0-selective-naming-line-maps-2";

    private final DecompiledSourceStore sourceStore;
    private final RuntimeSnapshotBytecodeSource bytecodeSource;
    private final JavaDecompiler javaDecompiler;
    private final ExecutorService worker;
    private final Map<String, CompletableFuture<Path>> inFlightRequests = new HashMap<>();
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
        return open(normalizedName, (sourceFile, origin) -> SourceFileNavigation.open(
                sourceFile,
                targetType,
                identifier,
                normalizedName,
                origin
        ));
    }

    public CompletableFuture<Path> openUsage(ReferenceUsage usage, ReferenceQuery query) {
        Objects.requireNonNull(usage, "usage");
        Objects.requireNonNull(query, "query");
        String binaryName = requireBinaryName(usage.location().className());
        return open(binaryName, (sourceFile, origin) -> SourceFileNavigation.openUsage(
                sourceFile,
                usage.location(),
                query,
                binaryName,
                origin
        ));
    }

    private CompletableFuture<Path> open(
            String binaryName,
            BiConsumer<Path, RuntimeSnapshotBytecodeSource.ClassOrigin> navigation
    ) {
        CompletableFuture<Path> task = decompile(binaryName);
        task.whenComplete((sourceFile, failure) -> {
            if (this.closed) {
                return;
            }
            if (failure == null) {
                try {
                    navigation.accept(sourceFile, this.bytecodeSource.findClassOrigin(binaryName));
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
        return task;
    }

    CompletableFuture<Path> decompile(String binaryName) {
        ensureOpen();
        Path cached = this.sourceStore.find(binaryName);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }
        synchronized (this.inFlightRequests) {
            CompletableFuture<Path> existing = this.inFlightRequests.get(binaryName);
            if (existing != null && !existing.isDone()) {
                return existing;
            }
            cached = this.sourceStore.find(binaryName);
            if (cached != null) {
                return CompletableFuture.completedFuture(cached);
            }

            CompletableFuture<Path> task = CompletableFuture.supplyAsync(() -> {
                try {
                    return decompileNow(binaryName);
                } catch (IOException exception) {
                    throw new CompletionException(exception);
                }
            }, this.worker);
            this.inFlightRequests.put(binaryName, task);
            task.whenComplete((ignored, failure) -> {
                synchronized (this.inFlightRequests) {
                    this.inFlightRequests.remove(binaryName, task);
                }
            });
            return task;
        }
    }

    private Path decompileNow(String binaryName) throws IOException {
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
        return this.sourceStore.write(binaryName, result.source(), result.lineMap());
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
