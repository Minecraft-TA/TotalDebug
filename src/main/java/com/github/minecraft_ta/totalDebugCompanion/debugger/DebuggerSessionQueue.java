package com.github.minecraft_ta.totalDebugCompanion.debugger;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** One serialized debugger lane with discardable, generation-bound inspection work. */
final class DebuggerSessionQueue implements AutoCloseable {
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> Thread.ofPlatform()
            .daemon()
            .name("Companion debugger session")
            .unstarted(task));
    private final AtomicLong advisoryGeneration = new AtomicLong();
    private volatile boolean closed;

    long advisoryGeneration() {
        return this.advisoryGeneration.get();
    }

    void invalidateAdvisoryWork() {
        this.advisoryGeneration.incrementAndGet();
    }

    <T> CompletableFuture<T> submit(Supplier<T> action) {
        return enqueue(Objects.requireNonNull(action, "action"), -1);
    }

    <T> CompletableFuture<T> submitAdvisory(long generation, Supplier<T> action) {
        return enqueue(Objects.requireNonNull(action, "action"), generation);
    }

    private <T> CompletableFuture<T> enqueue(Supplier<T> action, long requiredGeneration) {
        if (this.closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("Debugger session is closed"));
        }
        QueuedTask<T> task = new QueuedTask<>(action, requiredGeneration);
        try {
            this.worker.execute(task);
        } catch (RejectedExecutionException failure) {
            task.cancel("Debugger session is closed");
        }
        return task.result;
    }

    @Override
    public void close() {
        this.closed = true;
        this.advisoryGeneration.incrementAndGet();
        for (Runnable pending : this.worker.shutdownNow()) {
            if (pending instanceof QueuedTask<?> task) {
                task.cancel("Debugger session is closed");
            }
        }
    }

    private final class QueuedTask<T> implements Runnable {
        private final Supplier<T> action;
        private final long requiredGeneration;
        private final CompletableFuture<T> result = new CompletableFuture<>();

        private QueuedTask(Supplier<T> action, long requiredGeneration) {
            this.action = action;
            this.requiredGeneration = requiredGeneration;
        }

        @Override
        public void run() {
            if (this.requiredGeneration >= 0
                    && this.requiredGeneration != advisoryGeneration.get()) {
                cancel("Debugger inspection is stale");
                return;
            }
            try {
                this.result.complete(this.action.get());
            } catch (Throwable failure) {
                this.result.completeExceptionally(failure);
            }
        }

        private void cancel(String message) {
            this.result.completeExceptionally(new CancellationException(message));
        }
    }
}
