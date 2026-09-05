package com.github.minecraft_ta.totalDebugCompanion.debugger;

import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** One execution. Caller waits and cancellation requests do not replace its completion. */
public final class DebuggerEvaluation<T> {
    private final String id = UUID.randomUUID().toString();
    private final long started = System.nanoTime();
    private final CompletableFuture<T> completion = new CompletableFuture<>();
    private volatile boolean cancellationRequested;
    private volatile String state = "running";
    private volatile String error;
    private volatile long finished;

    public String id() { return this.id; }
    public CompletableFuture<T> completion() { return this.completion.copy(); }
    public boolean running() { return !this.completion.isDone(); }
    public boolean cancellationRequested() { return this.cancellationRequested; }
    public synchronized void cancel() { if (running()) this.cancellationRequested = true; }
    public void checkpoint() {
        if (this.cancellationRequested) throw new CancellationException("Evaluation cancelled before its next operation");
    }
    public synchronized Snapshot snapshot() {
        long elapsed = ((this.finished == 0 ? System.nanoTime() : this.finished) - this.started) / 1_000_000;
        return new Snapshot(this.id, this.state, elapsed, elapsed >= 5_000, this.cancellationRequested, this.error);
    }
    public synchronized void complete(T value, Throwable failure) {
        if (!running()) return;
        while (failure instanceof CompletionException && failure.getCause() != null) failure = failure.getCause();
        this.finished = System.nanoTime();
        this.state = failure == null ? "succeeded" : failure instanceof CancellationException ? "cancelled" : "failed";
        this.error = failure == null ? null : failure.toString();
        if (failure == null) this.completion.complete(value);
        else this.completion.completeExceptionally(failure);
    }
    public record Snapshot(String id, String state, long elapsedMillis, boolean slow,
                           boolean cancellationRequested, String error) { }
}
