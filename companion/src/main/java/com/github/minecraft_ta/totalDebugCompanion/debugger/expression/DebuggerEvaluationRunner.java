package com.github.minecraft_ta.totalDebugCompanion.debugger.expression;

import com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerEvaluation;
import com.sun.jdi.ThreadReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/** Owns target execution until it returns, independently of caller wait limits. */
final class DebuggerEvaluationRunner {
    private static final ThreadLocal<DebuggerEvaluation<?>> CURRENT = new ThreadLocal<>();
    private final DebuggerEvaluationLifecycle lifecycle = new DebuggerEvaluationLifecycle();
    private final AtomicReference<DebuggerEvaluation<?>> active = new AtomicReference<>();
    private volatile Runnable changed = () -> { };
    private final java.util.Map<String, DebuggerEvaluation<?>> history = new java.util.LinkedHashMap<>();
    private final java.util.function.Consumer<String> discard;

    DebuggerEvaluationRunner() { this(ignored -> { }); }
    DebuggerEvaluationRunner(java.util.function.Consumer<String> discard) { this.discard = discard; }

    static String currentId() {
        return java.util.Objects.requireNonNull(CURRENT.get(), "No active evaluation").id();
    }

    DebuggerEvaluation<?> operation(String id) {
        synchronized (this.history) { return this.history.get(id); }
    }

    void onChange(Runnable changed) { this.changed = changed; }
    DebuggerEvaluation<?> active() { return this.active.get(); }
    boolean isInEvaluation(ThreadReference thread) { return this.lifecycle.isInEvaluation(thread); }
    void clearState(ThreadReference thread) { this.lifecycle.clearState(thread); }
    static void checkpoint() {
        DebuggerEvaluation<?> operation = CURRENT.get();
        if (operation != null) operation.checkpoint();
    }
    <T> CompletableFuture<T> run(ThreadReference thread, Evaluation<T> action) {
        return start(thread, action).completion();
    }
    <T> DebuggerEvaluation<T> start(ThreadReference thread, Evaluation<T> action) {
        DebuggerEvaluation<T> operation = new DebuggerEvaluation<>();
        if (!this.active.compareAndSet(null, operation)) {
            throw new IllegalStateException("Debugger evaluation is still running; wait or request cancellation");
        }
        java.util.List<String> discarded = new java.util.ArrayList<>();
        synchronized (this.history) {
            this.history.put(operation.id(), operation);
            while (this.history.size() > 128) {
                String oldest = this.history.entrySet().stream().filter(entry -> !entry.getValue().running())
                        .map(java.util.Map.Entry::getKey).findFirst().orElse(null);
                if (oldest == null) break;
                this.history.remove(oldest);
                discarded.add(oldest);
            }
        }
        try {
            discarded.forEach(this::discard);
            this.lifecycle.begin(thread);
            notifyChanged();
            Thread.ofPlatform().daemon().name("debugger-evaluation").start(() -> {
                T value = null;
                Throwable failure = null;
                CURRENT.set(operation);
                try {
                    operation.checkpoint();
                    value = action.run();
                } catch (Throwable thrown) {
                    failure = thrown;
                } finally {
                    if (failure != null) discard(operation.id());
                    CURRENT.remove();
                    this.lifecycle.end(thread);
                    this.active.compareAndSet(operation, null);
                    operation.complete(value, failure);
                    notifyChanged();
                }
            });
        } catch (Throwable failure) {
            this.lifecycle.end(thread);
            this.active.compareAndSet(operation, null);
            operation.complete(null, failure);
            notifyChanged();
        }
        return operation;
    }

    private void notifyChanged() {
        try { this.changed.run(); }
        catch (RuntimeException observerFailure) {
            System.getLogger(DebuggerEvaluationRunner.class.getName()).log(System.Logger.Level.WARNING,
                    "Evaluation status listener failed", observerFailure);
        }
    }

    private void discard(String id) {
        try { this.discard.accept(id); }
        catch (RuntimeException failure) {
            System.getLogger(DebuggerEvaluationRunner.class.getName()).log(System.Logger.Level.WARNING,
                    "Unable to release retained evaluation values", failure);
        }
    }

    @FunctionalInterface
    interface Evaluation<T> { T run() throws Exception; }
}
