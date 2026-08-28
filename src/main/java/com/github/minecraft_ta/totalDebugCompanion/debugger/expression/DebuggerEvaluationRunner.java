package com.github.minecraft_ta.totalDebugCompanion.debugger.expression;

import com.microsoft.java.debug.core.IDebugSession;
import com.microsoft.java.debug.core.adapter.IDebugAdapterContext;
import com.sun.jdi.ThreadReference;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Bounds evaluation lifetime and aborts the debug session when target code does not return. */
final class DebuggerEvaluationRunner {
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    private static final ScheduledExecutorService TIMEOUTS = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("debugger-evaluation-timeout").factory()
    );

    private final DebuggerEvaluationLifecycle lifecycle = new DebuggerEvaluationLifecycle();
    private final Supplier<IDebugAdapterContext> debugContext;
    private final Duration timeout;

    DebuggerEvaluationRunner(Supplier<IDebugAdapterContext> debugContext) {
        this(debugContext, DEFAULT_TIMEOUT);
    }

    DebuggerEvaluationRunner(Supplier<IDebugAdapterContext> debugContext, Duration timeout) {
        this.debugContext = Objects.requireNonNull(debugContext, "debugContext");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Evaluation timeout must be positive");
        }
    }

    boolean isInEvaluation(ThreadReference thread) {
        return this.lifecycle.isInEvaluation(thread);
    }

    void clearState(ThreadReference thread) {
        this.lifecycle.clearState(thread);
    }

    <T> CompletableFuture<T> run(ThreadReference thread, Evaluation<T> operation) {
        CompletableFuture<T> result = new CompletableFuture<>();
        AtomicBoolean completed = new AtomicBoolean();
        CompletableFuture<T> invocation = CompletableFuture.supplyAsync(() -> {
            this.lifecycle.begin(thread);
            try {
                return operation.run();
            } catch (Exception exception) {
                throw new CompletionException(exception);
            } finally {
                this.lifecycle.end(thread);
            }
        });
        ScheduledFuture<?> timeoutTask = TIMEOUTS.schedule(
                () -> timeout(result, completed),
                this.timeout.toNanos(),
                TimeUnit.NANOSECONDS
        );
        invocation.whenComplete((value, failure) -> {
            if (!completed.compareAndSet(false, true)) {
                return;
            }
            timeoutTask.cancel(false);
            if (failure == null) {
                result.complete(value);
            } else {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    private <T> void timeout(CompletableFuture<T> result, AtomicBoolean completed) {
        if (!completed.compareAndSet(false, true)) {
            return;
        }
        TimeoutException failure = new TimeoutException(
                "Debugger evaluation exceeded " + this.timeout.toSeconds() + " seconds; the debug session was detached"
        );
        try {
            IDebugAdapterContext context = Objects.requireNonNull(
                    this.debugContext.get(), "Debug adapter context is unavailable during evaluation timeout"
            );
            IDebugSession session = Objects.requireNonNull(
                    context.getDebugSession(), "Debug session is unavailable during evaluation timeout"
            );
            session.detach();
        } catch (RuntimeException abortFailure) {
            failure.addSuppressed(abortFailure);
        }
        result.completeExceptionally(failure);
    }

    @FunctionalInterface
    interface Evaluation<T> {
        T run() throws Exception;
    }
}
