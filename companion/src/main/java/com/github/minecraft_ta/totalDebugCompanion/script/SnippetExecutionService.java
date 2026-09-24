package com.github.minecraft_ta.totalDebugCompanion.script;

import com.github.minecraft_ta.totaldebug.protocol.execution.ScriptExecutionEnvironment;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionResult;
import com.github.minecraft_ta.totalDebugCompanion.project.ProjectScope;
import com.github.minecraft_ta.totalDebugCompanion.jdt.JavaSnippetSource;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** Completes one future per transient Companion snippet run. */
public final class SnippetExecutionService implements AutoCloseable {
    private final Map<Integer, CompletableFuture<ExecutionResult>> runs = new ConcurrentHashMap<>();
    private volatile boolean closed;

    private final ExecutionRuns executions;
    private final ProjectScope project;
    private final ExecutionRuns.Observer observer = new ExecutionRuns.Observer() {
        @Override public void result(int id, ExecutionResult result) {
            if (!result.status().terminal()) return;
            CompletableFuture<ExecutionResult> completion = runs.remove(id);
            if (completion != null) completion.complete(result);
        }

        @Override public void disconnected(int id, boolean expected) {
            CompletableFuture<ExecutionResult> completion = runs.remove(id);
            if (completion != null) {
                completion.completeExceptionally(new IllegalStateException("Minecraft disconnected while the expression was running"));
            }
        }
    };

    public SnippetExecutionService(ExecutionRuns executions, ProjectScope project) {
        this.executions = executions;
        this.project = project;
    }

    public Execution execute(
            JavaSnippetSource.GeneratedSource source,
            Side side,
            ScriptExecutionEnvironment environment
    ) {
        return execute(source, side, environment, null);
    }

    /** Runs a snippet whose {@code target()} resolves {@code subject}, or has no target when null. */
    public Execution execute(
            JavaSnippetSource.GeneratedSource source,
            Side side,
            ScriptExecutionEnvironment environment,
            ScriptSubject subject
    ) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(environment, "environment");
        if (this.closed) {
            throw new IllegalStateException("Snippet execution service is closed");
        }
        source.requireExecutableSize();
        int id = this.executions.open(this.observer);
        CompletableFuture<ExecutionResult> completion = new CompletableFuture<>();
        this.runs.put(id, completion);
        if (!this.executions.submit(id, project, source.source(), side == Side.SERVER, environment, subject)) {
            this.runs.remove(id, completion);
            throw new IllegalStateException("Minecraft is not connected");
        }
        return new Execution(id, completion, () -> cancel(id));
    }

    private void cancel(int id) {
        if (this.runs.containsKey(id)) {
            this.executions.stop(id);
        }
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        this.closed = true;
        for (Map.Entry<Integer, CompletableFuture<ExecutionResult>> entry : this.runs.entrySet()) {
            this.executions.stop(entry.getKey());
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
