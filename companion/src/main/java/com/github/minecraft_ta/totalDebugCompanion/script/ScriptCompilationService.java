package com.github.minecraft_ta.totalDebugCompanion.script;

import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeIndexService.ReadySnapshot;
import com.github.minecraft_ta.totaldebug.evaluation.InMemoryJavaCompiler;
import com.github.minecraft_ta.totaldebug.evaluation.ServerManifest;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionResult;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionStatus;
import com.github.minecraft_ta.totaldebug.protocol.execution.ScriptBytecode;
import com.github.minecraft_ta.totaldebug.protocol.execution.ScriptExecutionEnvironment;
import com.github.minecraft_ta.totaldebug.protocol.scnet.RunScriptMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ServerManifestMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ServerSourceRequestMessage;
import com.github.minecraft_ta.totaldebug.storage.CacheFiles;
import com.github.minecraft_ta.totaldebug.storage.RuntimePhase;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** Compiles off the UI thread; Minecraft remains responsible for loading and running the result. */
public final class ScriptCompilationService implements AutoCloseable {
    public record CompilationResult(ScriptBytecode bytecode, String inventoryId) {}

    private static final Pattern SCRIPT_CLASS = Pattern.compile(
            "\\bpublic\\s+(?:final\\s+)?class\\s+([\\p{javaJavaIdentifierStart}][\\p{javaJavaIdentifierPart}]*)"
                    + "\\s+extends\\s+(?:com\\.github\\.minecraft_ta\\.totaldebug\\.script\\.)?ScriptProgram\\b");
    private final Object compilerLock = new Object();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Companion script compiler");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<Integer, Pending> pending = new ConcurrentHashMap<>();
    private final Predicate<RunScriptMessage> sender;
    private volatile ReadySnapshot snapshot;
    private InMemoryJavaCompiler compiler;
    private volatile boolean closed;
    private record ServerSnapshot(String sessionId, String inventoryId, Set<String> unsupported) {}
    private record Baseline(String sessionId, ServerManifest manifest) {}
    private record Comparison(String requestId, ReadySnapshot selected, ServerCompatibility work) {}
    private final Predicate<ServerSourceRequestMessage> sourceRequester;
    private volatile ServerSnapshot serverSnapshot;
    private volatile String serverUnavailable = "No server handshake is available";
    private final ServerManifestMessage.Assembler manifestTransfer = new ServerManifestMessage.Assembler();
    private final ServerManifestMessage.Assembler detailTransfer = new ServerManifestMessage.Assembler();
    private long serverGeneration;
    private long manifestGeneration;
    private Baseline baseline;
    private Comparison comparison;
    private Set<String> compilingForServer;

    public ScriptCompilationService(Predicate<RunScriptMessage> sender,
                                    Predicate<ServerSourceRequestMessage> sourceRequester) {
        this.sender = sender;
        this.sourceRequester = sourceRequester;
    }

    public synchronized void acceptServerManifest(ServerManifestMessage message) {
        if (this.closed) return;
        if (!message.baseline() && (this.baseline == null || this.comparison == null
                || !this.baseline.sessionId().equals(message.sessionId())
                || !this.comparison.requestId().equals(message.requestId())
                || this.comparison.work().nextSource() != message.source())) return;
        if (message.baseline() && message.offset() == 0) {
            this.serverGeneration++;
            this.manifestTransfer.clear();
            invalidateComparison();
            this.baseline = null;
            this.serverUnavailable = message.total() == 0 ? message.detail() : "Preparing server class compatibility";
        }
        if (!message.baseline() && message.total() == 0) {
            failComparison(this.manifestGeneration, message.detail());
            return;
        }
        byte[] bytes;
        try { bytes = (message.baseline() ? this.manifestTransfer : this.detailTransfer).accept(message); }
        catch (IllegalArgumentException exception) {
            failComparison(this.manifestGeneration, exception.getMessage());
            return;
        }
        if (bytes == null) return;
        long generation = this.manifestGeneration;
        long serverGeneration = this.serverGeneration;
        this.worker.execute(() -> {
            try {
                if (message.baseline()) {
                    var decoded = ServerManifest.decode(bytes);
                    long currentGeneration;
                    synchronized (this) {
                        if (this.closed || serverGeneration != this.serverGeneration) return;
                        this.baseline = new Baseline(message.sessionId(), decoded);
                        currentGeneration = this.manifestGeneration;
                    }
                    prepareComparison(currentGeneration);
                } else {
                    var details = ServerManifest.decodeDetails(bytes);
                    synchronized (this) {
                        if (this.closed || generation != this.manifestGeneration || this.comparison == null) return;
                        this.comparison.work().accept(message.source(), details);
                    }
                    advanceComparison(generation);
                }
            } catch (Exception exception) {
                synchronized (this) {
                    if (message.baseline() && serverGeneration != this.serverGeneration) return;
                    failComparison(message.baseline() ? this.manifestGeneration : generation, exception.getMessage());
                }
            }
        });
    }

    private void prepareComparison(long generation) {
        ReadySnapshot selected;
        Baseline baseline;
        synchronized (this) {
            if (this.closed || generation != this.manifestGeneration) return;
            selected = this.snapshot;
            baseline = this.baseline;
        }
        if (selected == null || baseline == null) return;
        try {
            ServerCompatibility work = CacheFiles.locked(selected.indexFile().getParent(), () -> {
                CacheFiles.requireIdentity(selected.indexFile().getParent().resolve("inventory.json"), "id", selected.inventoryId());
                try (var phase = RuntimePhase.start("server.local-baseline")) {
                    return new ServerCompatibility(baseline.manifest(), selected.sources());
                }
            });
            synchronized (this) {
                if (this.closed || generation != this.manifestGeneration || this.snapshot != selected) return;
                this.comparison = new Comparison(UUID.randomUUID().toString(), selected, work);
            }
            advanceComparison(generation);
        } catch (Exception exception) { failComparison(generation, exception.getMessage()); }
    }

    private void advanceComparison(long generation) throws Exception {
        Comparison current;
        Baseline baseline;
        synchronized (this) {
            if (this.closed || generation != this.manifestGeneration || this.comparison == null) return;
            current = this.comparison;
            baseline = this.baseline;
            int source = current.work().nextSource();
            if (source != -1) {
                this.serverUnavailable = "Comparing server source " + baseline.manifest().sources().get(source).name();
                if (!this.sourceRequester.test(new ServerSourceRequestMessage(baseline.sessionId(), current.requestId(), source))) {
                    throw new IOException("Minecraft disconnected before server source details could be requested");
                }
                return;
            }
        }
        Set<String> unsupported = CacheFiles.locked(current.selected().indexFile().getParent(), () -> {
            synchronized (this.compilerLock) {
                if (this.closed || this.snapshot != current.selected()) throw new IOException("The client inventory changed");
                CacheFiles.requireIdentity(current.selected().indexFile().getParent().resolve("inventory.json"),
                        "id", current.selected().inventoryId());
                try (var phase = RuntimePhase.start("server.compare-declarations")) {
                    return current.work().finish(current.selected());
                }
            }
        });
        synchronized (this) {
            if (this.closed || generation != this.manifestGeneration || this.comparison != current
                    || this.snapshot != current.selected()) return;
            this.serverSnapshot = new ServerSnapshot(baseline.sessionId(), current.selected().inventoryId(), unsupported);
            this.comparison = null;
        }
    }

    private synchronized void failComparison(long generation, String detail) {
        if (generation != this.manifestGeneration) return;
        invalidateComparison();
        this.serverUnavailable = detail == null ? "Server class comparison failed" : detail;
    }

    private void invalidateComparison() {
        this.manifestGeneration++;
        this.serverSnapshot = null;
        this.comparison = null;
        this.detailTransfer.clear();
    }

    /** Must complete before the old native index is closed by its owner. */
    public void bind(ReadySnapshot snapshot) {
        synchronized (this.compilerLock) {
            if (this.compiler != null) {
                try { this.compiler.close(); }
                catch (IOException exception) { throw new IllegalStateException("Unable to close the script compiler", exception); }
            }
            this.compiler = null;
            this.snapshot = snapshot;
            if (snapshot != null) {
                this.compiler = new InMemoryJavaCompiler(standard ->
                        new IndexedJavaFileManager(standard, snapshot.index(), snapshot.sources(), () -> this.compilingForServer));
            }
            synchronized (this) {
                invalidateComparison();
                this.serverUnavailable = "Waiting for the client index and server handshake comparison";
                long generation = this.manifestGeneration;
                if (!this.closed && snapshot != null && this.baseline != null) {
                    this.worker.execute(() -> prepareComparison(generation));
                }
            }
        }
    }

    /** Compiles a named Java class without submitting it for execution. */
    public CompletableFuture<CompilationResult> compile(String source, String entryClass) {
        ReadySnapshot selected = this.snapshot;
        if (this.closed || selected == null) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "The runtime class index is not ready for compilation"));
        }
        var result = new CompletableFuture<CompilationResult>();
        try {
            this.worker.execute(() -> {
                try {
                    CompilationResult compiled = compileSelected(selected, null, source, entryClass, result::isCancelled);
                    if (this.closed || this.snapshot != selected) {
                        throw new IllegalStateException("The runtime changed during compilation");
                    }
                    result.complete(compiled);
                } catch (Exception exception) {
                    result.completeExceptionally(exception);
                }
            });
        } catch (RuntimeException exception) {
            result.completeExceptionally(exception);
        }
        return result;
    }

    public boolean isCurrentInventory(String inventoryId) {
        ReadySnapshot current = this.snapshot;
        return !this.closed && current != null && current.inventoryId().equals(inventoryId);
    }

    public void submit(int id, String source, boolean serverSide, ScriptExecutionEnvironment environment,
                       Consumer<ExecutionResult> failureHandler) {
        ReadySnapshot selected = this.snapshot;
        if (this.closed || selected == null) {
            failureHandler.accept(failure("The runtime class index is not ready for compilation"));
            return;
        }
        ServerSnapshot server = serverSide ? this.serverSnapshot : null;
        if (serverSide && (server == null || !server.inventoryId().equals(selected.inventoryId()))) {
            failureHandler.accept(failure(this.serverUnavailable));
            return;
        }
        var task = new Pending(failureHandler);
        if (this.pending.putIfAbsent(id, task) != null) {
            failureHandler.accept(failure("A script with this id is already compiling"));
            return;
        }
        synchronized (task) {
            try {
                task.future = this.worker.submit(() -> compileAndSend(id, source, serverSide, environment, selected, server, task));
            } catch (RuntimeException exception) {
                this.pending.remove(id, task);
                failureHandler.accept(failure("Unable to start compilation: " + exception.getMessage()));
            }
        }
    }

    private void compileAndSend(int id, String source, boolean serverSide, ScriptExecutionEnvironment environment,
                                ReadySnapshot selected, ServerSnapshot server, Pending task) {
        try {
            var matcher = SCRIPT_CLASS.matcher(source);
            if (!matcher.find()) throw new IllegalArgumentException(
                    "Script source must contain a public class that directly extends ScriptProgram");
            String primaryClass = matcher.group(1);
            CompilationResult compiled = compileSelected(selected, server, source, primaryClass,
                    () -> this.pending.get(id) != task);
            synchronized (task) {
                if (this.pending.get(id) != task) return;
                if (this.snapshot != selected || (server != null && this.serverSnapshot != server) || !this.sender.test(new RunScriptMessage(
                        id, compiled.bytecode(), compiled.inventoryId(), serverSide, environment.name(), server == null ? "" : server.sessionId()))) {
                    throw new IllegalStateException("Minecraft disconnected or the runtime changed before the script was submitted");
                }
                this.pending.remove(id, task);
            }
        } catch (Exception exception) {
            if (this.pending.remove(id, task)) task.failureHandler.accept(failure(exception.getMessage()));
        }
    }

    private CompilationResult compileSelected(ReadySnapshot selected, ServerSnapshot server, String source, String entryClass,
                                               BooleanSupplier cancelled) throws Exception {
        return CacheFiles.locked(selected.indexFile().getParent(), () -> {
            synchronized (this.compilerLock) {
                if (this.closed || this.snapshot != selected || (server != null && this.serverSnapshot != server) || cancelled.getAsBoolean()) {
                    throw new IllegalStateException("The runtime changed or compilation was cancelled");
                }
                CacheFiles.requireIdentity(selected.indexFile().getParent().resolve("inventory.json"),
                        "id", selected.inventoryId());
                this.compilingForServer = server == null ? null : server.unsupported();
                try {
                    return new CompilationResult(new ScriptBytecode(entryClass,
                            this.compiler.compile(source, entryClass, "")), selected.inventoryId());
                } finally {
                    this.compilingForServer = null;
                }
            }
        });
    }

    /** Returns true when cancellation was handled before any bytecode was sent. */
    public boolean cancel(int id) {
        Pending task = this.pending.get(id);
        if (task == null) return false;
        synchronized (task) {
            if (!this.pending.remove(id, task)) return false;
            if (task.future != null) task.future.cancel(true);
        }
        task.failureHandler.accept(ExecutionResult.failed("", null, "Script run cancelled before execution"));
        return true;
    }

    public void runtimeDisconnected() {
        synchronized (this) {
            this.serverGeneration++;
            this.manifestTransfer.clear();
            invalidateComparison();
            this.baseline = null;
            this.serverUnavailable = "Minecraft disconnected";
        }
        for (int id : this.pending.keySet()) cancel(id);
    }

    @Override
    public void close() {
        this.closed = true;
        runtimeDisconnected();
        // Drain queued compile-only requests so their futures complete with the closed-state failure.
        this.worker.shutdown();
        bind(null);
    }

    private static ExecutionResult failure(String message) {
        return ExecutionResult.fromStatus(ExecutionStatus.COMPILATION_FAILED,
                message == null ? "Script compilation failed" : message);
    }

    private static final class Pending {
        private final Consumer<ExecutionResult> failureHandler;
        private Future<?> future;

        private Pending(Consumer<ExecutionResult> failureHandler) {
            this.failureHandler = failureHandler;
        }
    }
}
