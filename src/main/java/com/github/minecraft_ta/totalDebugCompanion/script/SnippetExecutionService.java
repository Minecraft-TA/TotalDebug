package com.github.minecraft_ta.totalDebugCompanion.script;

import com.github.minecraft_ta.totalDebugCompanion.CompanionApp;
import com.github.minecraft_ta.totalDebugCompanion.jdt.JavaSnippetSource;
import com.github.minecraft_ta.totalDebugCompanion.messages.script.RunScriptMessage;
import com.github.minecraft_ta.totalDebugCompanion.messages.script.ExecutionResultMessage;
import com.github.minecraft_ta.totalDebugCompanion.messages.script.StopScriptMessage;

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

    public SnippetExecutionService() {
        CompanionApp.SERVER.getMessageBus().listenAlways(
                ExecutionResultMessage.class,
                this,
                this::acceptResult
        );
    }

    public Execution execute(
            JavaSnippetSource.GeneratedSource source,
            Side side,
            RunScriptMessage.ExecutionEnvironment environment
    ) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(environment, "environment");
        if (this.closed) {
            throw new IllegalStateException("Snippet execution service is closed");
        }
        if (!CompanionApp.SERVER.isClientConnected()) {
            throw new IllegalStateException("Minecraft is not connected");
        }
        source.requireExecutableSize();
        int id = nextId();
        CompletableFuture<ExecutionResult> completion = new CompletableFuture<>();
        this.runs.put(id, completion);
        boolean sent = CompanionApp.send(new RunScriptMessage(
                id,
                source.source(),
                side == Side.SERVER,
                environment
        ));
        if (!sent) {
            this.runs.remove(id, completion);
            throw new IllegalStateException("Minecraft disconnected while the expression was submitted");
        }
        return new Execution(id, completion, () -> cancel(id));
    }

    private int nextId() {
        while (true) {
            int id = this.nextId.getAndDecrement();
            if (id <= 1_000_000_000) {
                throw new IllegalStateException("Transient snippet id space exhausted");
            }
            if (!this.runs.containsKey(id)) {
                return id;
            }
        }
    }

    private void cancel(int id) {
        if (this.runs.containsKey(id)) {
            CompanionApp.send(new StopScriptMessage(id));
        }
    }

    private void acceptResult(ExecutionResultMessage message) {
        ExecutionResult result = message.getResult();
        if (result.status() == ExecutionResult.Status.COMPILATION_COMPLETED) {
            return;
        }
        CompletableFuture<ExecutionResult> completion = this.runs.remove(message.getScriptId());
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
        CompanionApp.SERVER.getMessageBus().unregister(ExecutionResultMessage.class, this);
        for (Map.Entry<Integer, CompletableFuture<ExecutionResult>> entry : this.runs.entrySet()) {
            CompanionApp.send(new StopScriptMessage(entry.getKey()));
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
            completion = Objects.requireNonNull(completion, "completion");
            cancel = Objects.requireNonNull(cancel, "cancel");
        }
    }

}
