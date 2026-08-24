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
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class CompanionDecompilationService implements AutoCloseable {
    private static final String CACHE_FORMAT = "1";

    private final String runtimeSignature;
    private final Path outputDirectory;
    private final Path cacheDirectory;
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
        this.runtimeSignature = requireNonBlank(runtimeSignature, "runtimeSignature");
        Path root = Objects.requireNonNull(dataDirectory, "dataDirectory").toAbsolutePath().normalize();
        this.outputDirectory = root.resolve("decompiled-files");
        Path cacheRoot = root.resolve(".decompiler-cache");
        this.cacheDirectory = cacheRoot.resolve(this.runtimeSignature).resolve(CACHE_FORMAT).normalize();
        if (!this.cacheDirectory.startsWith(cacheRoot)) {
            throw new IllegalArgumentException("runtimeSignature is not a safe cache key");
        }
        this.bytecodeSource = Objects.requireNonNull(bytecodeSource, "bytecodeSource");
        this.javaDecompiler = Objects.requireNonNull(javaDecompiler, "javaDecompiler");
        this.worker = Executors.newSingleThreadExecutor(task -> Thread.ofPlatform()
                .daemon()
                .name("Companion decompiler")
                .unstarted(task));
        Files.createDirectories(this.outputDirectory);
        Files.createDirectories(this.cacheDirectory);
    }

    public CompletableFuture<Path> openClass(String binaryName, int targetType, String targetIdentifier) {
        ensureOpen();
        String normalizedName = requireBinaryName(binaryName);
        String identifier = Objects.requireNonNullElse(targetIdentifier, "");
        return open(normalizedName, sourceFile -> SourceFileNavigation.open(sourceFile, targetType, identifier));
    }

    public CompletableFuture<Path> openUsage(ReferenceUsage usage, ReferenceQuery query) {
        Objects.requireNonNull(usage, "usage");
        Objects.requireNonNull(query, "query");
        String binaryName = requireBinaryName(usage.location().className());
        return open(binaryName, sourceFile -> SourceFileNavigation.openUsage(sourceFile, usage.location(), query));
    }

    private CompletableFuture<Path> open(String binaryName, Consumer<Path> navigation) {
        CompletableFuture<Path> task = decompile(binaryName);
        task.whenComplete((sourceFile, failure) -> {
            if (this.closed) {
                return;
            }
            if (failure == null) {
                try {
                    navigation.accept(sourceFile);
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
        synchronized (this.inFlightRequests) {
            CompletableFuture<Path> existing = this.inFlightRequests.get(binaryName);
            if (existing != null && !existing.isDone()) {
                return existing;
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
        byte[] targetBytes = this.bytecodeSource.findClassBytes(binaryName);
        if (targetBytes == null) {
            throw new IOException("Class not found in runtime snapshot: " + binaryName);
        }

        String bytecodeHash = sha256(targetBytes);
        Path cacheFile = this.cacheDirectory.resolve(bytecodeHash).resolve(binaryName + ".java");
        Path outputFile = this.outputDirectory.resolve(binaryName + ".java");
        if (Files.isRegularFile(cacheFile)) {
            writeAtomically(outputFile, Files.readString(cacheFile, StandardCharsets.UTF_8));
            return outputFile;
        }

        DecompilationResult result = this.javaDecompiler.decompile(binaryName, this.bytecodeSource);
        for (DecompilerDiagnostic diagnostic : result.diagnostics()) {
            System.err.println("Vineflower " + diagnostic.severity().name().toLowerCase()
                    + " for " + binaryName + ": " + diagnostic.message());
        }
        if (!result.isComplete()) {
            throw new IOException("Vineflower produced partial source for " + binaryName);
        }

        Files.createDirectories(cacheFile.getParent());
        writeAtomically(cacheFile, result.source());
        writeAtomically(outputFile, result.source());
        return outputFile;
    }

    private static void writeAtomically(Path target, String content) throws IOException {
        Files.createDirectories(Objects.requireNonNull(target.getParent(), "target has no parent"));
        Path staged = Files.createTempFile(target.getParent(), ".decompiled-", ".tmp");
        try {
            Files.writeString(staged, content, StandardCharsets.UTF_8);
            try {
                Files.move(
                        staged,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
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
