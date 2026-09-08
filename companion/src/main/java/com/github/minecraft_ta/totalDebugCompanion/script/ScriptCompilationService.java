package com.github.minecraft_ta.totalDebugCompanion.script;

import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeIndexService.ReadySnapshot;
import com.github.minecraft_ta.totaldebug.evaluation.InMemoryJavaCompiler;
import com.github.minecraft_ta.totaldebug.evaluation.ServerManifest;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ServerManifestMessage;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionResult;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionStatus;
import com.github.minecraft_ta.totaldebug.protocol.execution.ScriptBytecode;
import com.github.minecraft_ta.totaldebug.protocol.execution.ScriptExecutionEnvironment;
import com.github.minecraft_ta.totaldebug.protocol.scnet.RunScriptMessage;
import com.github.minecraft_ta.totaldebug.storage.CacheFiles;

import java.io.IOException;
import java.util.Map;
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
    private record ServerSnapshot(String sessionId, ServerManifest manifest) {}
    private volatile ServerSnapshot serverSnapshot;
    private volatile String serverUnavailable = "No server handshake is available";
    private final ServerManifestMessage.Assembler manifestTransfer = new ServerManifestMessage.Assembler();
    private long manifestGeneration;
    private ServerManifest compilingForServer;

    public synchronized void acceptServerManifest(ServerManifestMessage message) {
        if (this.closed) return;
        if (message.offset() == 0) {
            this.manifestGeneration++;
            this.serverSnapshot = null;
            this.serverUnavailable = message.total() == 0 ? message.detail() : "Preparing server class compatibility";
        }
        byte[] bytes;
        try {
            bytes = this.manifestTransfer.accept(message);
        } catch (IllegalArgumentException exception) {
            this.manifestGeneration++;
            this.serverSnapshot = null;
            this.serverUnavailable = exception.getMessage();
            return;
        }
        if (bytes == null) return;
        long generation = this.manifestGeneration;
        this.worker.execute(() -> {
            try {
                var manifest = ServerManifest.decode(bytes);
                synchronized (this) {
                    if (!this.closed && generation == this.manifestGeneration) {
                        this.serverSnapshot = new ServerSnapshot(message.sessionId(), manifest);
                    }
                }
            } catch (IOException exception) {
                synchronized (this) {
                    if (generation == this.manifestGeneration) this.serverUnavailable = exception.getMessage();
                }
            }
        });
    }

    public ScriptCompilationService(Predicate<RunScriptMessage> sender) {
        this.sender = sender;
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
        if (serverSide && server == null) {
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
                this.compilingForServer = server == null ? null : server.manifest();
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
            this.manifestGeneration++;
            this.serverSnapshot = null;
            this.serverUnavailable = "Minecraft disconnected";
            this.manifestTransfer.clear();
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
