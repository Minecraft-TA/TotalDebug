package com.github.minecraft_ta.totalDebugCompanion.script;

import com.github.minecraft_ta.totalDebugCompanion.runtime.RuntimeIndexService.ReadySnapshot;
import com.github.minecraft_ta.totaldebug.evaluation.InMemoryJavaCompiler;
import com.github.minecraft_ta.totaldebug.evaluation.InMemoryCompilationException;
import com.github.minecraft_ta.totaldebug.evaluation.CompilationDiagnostic;
import com.github.minecraft_ta.totaldebug.protocol.Side;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionResult;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionStatus;
import com.github.minecraft_ta.totaldebug.protocol.execution.ScriptBytecode;
import com.github.minecraft_ta.totaldebug.protocol.execution.ScriptExecutionEnvironment;
import com.github.minecraft_ta.totaldebug.protocol.scnet.RunScriptMessage;
import com.github.minecraft_ta.totaldebug.storage.CacheFiles;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
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
    public record Failure(ExecutionResult result, List<CompilationDiagnostic> diagnostics) {
        public Failure { diagnostics = List.copyOf(diagnostics); }
    }

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
    /** Why server runs cannot start before Companion knows the game plays a server that runs its scripts. */
    public static final String NO_SERVER = "Server scripts need a singleplayer world or a server with TotalDebug";
    private final Predicate<RunScriptMessage> sender;
    private volatile ReadySnapshot snapshot;
    private InMemoryJavaCompiler compiler;
    private volatile boolean closed;
    /**
     * Whether the game's server runs this player's scripts: {@code refusal} is empty when it does. Replaced on every
     * change, so a server compilation queued before one is not sent after it.
     */
    private record ServerAccess(String refusal) {}
    private volatile ServerAccess serverAccess = new ServerAccess(NO_SERVER);
    private CompletableFuture<Void> readinessChange = new CompletableFuture<>();
    /** Identical source compiled against the same runtime yields identical bytecode. */
    private record CompiledKey(ReadySnapshot selected, String source, String entryClass) {}
    private static final int MAX_COMPILED = 64;
    private final Map<CompiledKey, CompilationResult> compiled = new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<CompiledKey, CompilationResult> eldest) {
            return size() > MAX_COMPILED;
        }
    };

    /**
     * Whether a run on one side can compile now, and otherwise why not. {@code changed} completes on the next change
     * of the index, the server's answer or the connection, so a caller can wait instead of failing.
     */
    public record Readiness(boolean ready, String detail, CompletableFuture<Void> changed) {
    }

    public synchronized Readiness readiness(Side side) {
        String detail = this.closed || this.snapshot == null ? "The runtime class index is not ready for compilation"
                : side == Side.SERVER ? this.serverAccess.refusal()
                : "";
        return new Readiness(detail.isEmpty(), detail, this.readinessChange.copy());
    }

    /** Called with this service's lock held whenever readiness or its reason may have changed. */
    private void readinessChanged() {
        CompletableFuture<Void> previous = this.readinessChange;
        this.readinessChange = new CompletableFuture<>();
        previous.complete(null);
    }

    public ScriptCompilationService(Predicate<RunScriptMessage> sender) {
        this.sender = sender;
    }

    /**
     * What the game's server answered about running this player's scripts, or why it cannot be asked: {@code refusal}
     * is empty when server runs may start. The server checks each run's references itself.
     */
    public synchronized void serverAccess(String refusal) {
        this.serverAccess = new ServerAccess(refusal);
        readinessChanged();
    }

    /** Revoke compilation immediately; the runtime owner can retain its index for cached browsing. */
    public void suspendRuntime() {
        this.snapshot = null;
        synchronized (this) {
            readinessChanged();
        }
        for (int id : this.pending.keySet()) cancel(id);
    }

    /** Must complete before the old native index is closed by its owner. */
    public void bind(ReadySnapshot snapshot) {
        if (snapshot != null && !snapshot.isRuntime()) throw new IllegalArgumentException("Script compilation requires a runtime inventory");
        synchronized (this.compilerLock) {
            if (this.compiler != null) {
                try { this.compiler.close(); }
                catch (IOException exception) { throw new IllegalStateException("Unable to close the script compiler", exception); }
            }
            this.compiler = null;
            this.snapshot = snapshot;
            this.compiled.clear();
            if (snapshot != null) {
                this.compiler = new InMemoryJavaCompiler(standard ->
                        new IndexedJavaFileManager(standard, snapshot.index(), snapshot.sources()));
            }
            synchronized (this) {
                readinessChanged();
            }
        }
    }

    public boolean hasRuntime() { return !closed && snapshot != null; }

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
                    CompilationResult compiled = compileSelected(selected, source, entryClass, result::isCancelled);
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

    public void submit(int id, String source, Side side, ScriptExecutionEnvironment environment,
                       Consumer<Failure> failureHandler) {
        submit(id, source, side, environment, null, failureHandler);
    }

    /** Compiles and sends a run whose {@code target()} resolves {@code subject}, or has no target when null. */
    public void submit(int id, String source, Side side, ScriptExecutionEnvironment environment,
                       ScriptSubject subject, Consumer<Failure> failureHandler) {
        ReadySnapshot selected = this.snapshot;
        if (this.closed || selected == null) {
            failureHandler.accept(failure("The runtime class index is not ready for compilation"));
            return;
        }
        ServerAccess server = side == Side.SERVER ? this.serverAccess : null;
        if (server != null && !server.refusal().isEmpty()) {
            failureHandler.accept(failure(server.refusal()));
            return;
        }
        var task = new Pending(failureHandler);
        if (this.pending.putIfAbsent(id, task) != null) {
            failureHandler.accept(failure("A script with this id is already compiling"));
            return;
        }
        synchronized (task) {
            try {
                task.future = this.worker.submit(() -> compileAndSend(id, source, side, environment, subject, selected, server, task));
            } catch (RuntimeException exception) {
                this.pending.remove(id, task);
                failureHandler.accept(failure("Unable to start compilation: " + exception.getMessage()));
            }
        }
    }

    private void compileAndSend(int id, String source, Side side, ScriptExecutionEnvironment environment,
                                ScriptSubject subject, ReadySnapshot selected, ServerAccess server, Pending task) {
        try {
            var matcher = SCRIPT_CLASS.matcher(source);
            if (!matcher.find()) throw new IllegalArgumentException(
                    "Script source must contain a public class that directly extends ScriptProgram");
            String primaryClass = matcher.group(1);
            CompilationResult compiled = compileSelected(selected, source, primaryClass,
                    () -> this.pending.get(id) != task);
            synchronized (task) {
                if (this.pending.get(id) != task) return;
                if (this.snapshot != selected || (server != null && this.serverAccess != server) || !this.sender.test(new RunScriptMessage(
                        id, compiled.bytecode(), compiled.inventoryId(), side, environment.name(),
                        subject == null ? "" : subject.subject().format(), subject == null ? "" : subject.gameSessionId(),
                        subject == null ? "" : subject.expectedId()))) {
                    throw new IllegalStateException("Minecraft disconnected or the runtime changed before the script was submitted");
                }
                this.pending.remove(id, task);
            }
        } catch (Exception exception) {
            if (this.pending.remove(id, task)) task.failureHandler.accept(new Failure(failure(exception.getMessage()).result(),
                    exception instanceof InMemoryCompilationException compilation ? compilation.diagnostics() : List.of()));
        }
    }

    private CompilationResult compileSelected(ReadySnapshot selected, String source, String entryClass,
                                               BooleanSupplier cancelled) throws Exception {
        CompiledKey key = new CompiledKey(selected, source, entryClass);
        synchronized (this.compilerLock) {
            CompilationResult cached = this.compiled.get(key);
            if (cached != null && !this.closed && this.snapshot == selected) {
                return cached;
            }
        }
        return CacheFiles.locked(selected.indexFile().getParent(), () -> {
            synchronized (this.compilerLock) {
                if (this.closed || this.snapshot != selected || cancelled.getAsBoolean()) {
                    throw new IllegalStateException("The runtime changed or compilation was cancelled");
                }
                CacheFiles.requireIdentity(selected.indexFile().getParent().resolve("inventory.json"),
                        "id", selected.inventoryId());
                CompilationResult result = new CompilationResult(new ScriptBytecode(entryClass,
                        this.compiler.compile(source, entryClass, "")), selected.inventoryId());
                this.compiled.put(key, result);
                return result;
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
        task.failureHandler.accept(new Failure(ExecutionResult.failed("", null, "Script run cancelled before execution"), List.of()));
        return true;
    }

    public void runtimeDisconnected() {
        serverAccess("Minecraft disconnected");
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

    private static Failure failure(String message) {
        return new Failure(ExecutionResult.fromStatus(ExecutionStatus.COMPILATION_FAILED,
                message == null ? "Script compilation failed" : message), List.of());
    }

    private static final class Pending {
        private final Consumer<Failure> failureHandler;
        private Future<?> future;

        private Pending(Consumer<Failure> failureHandler) {
            this.failureHandler = failureHandler;
        }
    }
}
