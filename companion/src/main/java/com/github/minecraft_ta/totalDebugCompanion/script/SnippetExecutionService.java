package com.github.minecraft_ta.totalDebugCompanion.script;

import com.github.minecraft_ta.totaldebug.protocol.execution.ScriptExecutionEnvironment;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionResult;
import com.github.minecraft_ta.totalDebugCompanion.project.ProjectScope;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionSession;
import java.util.function.Consumer;
import com.github.minecraft_ta.totalDebugCompanion.jdt.JavaSnippetSource;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ExecutionResultMessage;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Owns transient Companion snippet runs and correlates their terminal status messages. */
public final class SnippetExecutionService implements AutoCloseable {
    private final AtomicInteger nextId = new AtomicInteger(Integer.MAX_VALUE);
    private final Map<Integer, CompletableFuture<ExecutionResult>> runs = new ConcurrentHashMap<>();
    private volatile boolean closed;

    private final CompanionSession session;
    private final ScriptExecutionService scripts;
    private final ProjectScope project;
    private final Consumer<ExecutionResultMessage> listener = this::acceptResult;

    public SnippetExecutionService(CompanionSession session, ScriptExecutionService scripts, ProjectScope project) {
        this.session = session;
        this.scripts = scripts;
        this.project = project;
        session.addExecutionResultListener(this.listener);
    }

    public Execution execute(
            JavaSnippetSource.GeneratedSource source,
            Side side,
            ScriptExecutionEnvironment environment
    ) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(environment, "environment");
        if (this.closed) {
            throw new IllegalStateException("Snippet execution service is closed");
        }
        if (!scripts.isConnected()) {
            throw new IllegalStateException("Minecraft is not connected");
        }
        source.requireExecutableSize();
        int id = nextId();
        CompletableFuture<ExecutionResult> completion = new CompletableFuture<>();
        this.runs.put(id, completion);
        boolean sent = scripts.run(
                project,
                id,
                source.source(),
                side == Side.SERVER,
                environment,
                result -> acceptResult(new ExecutionResultMessage(id, result))
        );
        if (!sent) {
            this.runs.remove(id, completion);
            throw new IllegalStateException("Minecraft disconnected while the expression was submitted");
        }
        return new Execution(id, completion, () -> cancel(id));
    }

    private int nextId() {
        int id = this.nextId.getAndDecrement();
        if (id <= 1_000_000_000) {
            throw new IllegalStateException("Transient snippet id space exhausted");
        }
        return id;
    }

    private void cancel(int id) {
        if (this.runs.containsKey(id)) {
            scripts.stop(id);
        }
    }

    private void acceptResult(ExecutionResultMessage message) {
        ExecutionResult result = message.result();
        if (!result.status().terminal()) {
            return;
        }
        CompletableFuture<ExecutionResult> completion = this.runs.remove(message.scriptId());
        if (completion == null) {
            return;
        }
        completion.complete(result);
    }

    public void runtimeDisconnected() {
        IllegalStateException failure = new IllegalStateException(
                "Minecraft disconnected while the expression was running"
        );
        for (CompletableFuture<ExecutionResult> completion : this.runs.values()) {
            completion.completeExceptionally(failure);
        }
        this.runs.clear();
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        session.removeExecutionResultListener(this.listener);
        for (Map.Entry<Integer, CompletableFuture<ExecutionResult>> entry : this.runs.entrySet()) {
            scripts.stop(entry.getKey());
            entry.getValue().completeExceptionally(new IllegalStateException("Snippet execution service closed"));
        }
        this.runs.clear();
    }

    public enum Side {
        CLIENT,
        SERVER
    }

    public record Execution(int id, CompletableFuture<ExecutionResult> completion, Runnable cancel) {
        public Execution {
            Objects.requireNonNull(completion, "completion");
            Objects.requireNonNull(cancel, "cancel");
        }
    }

}
