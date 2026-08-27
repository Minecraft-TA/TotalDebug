package com.github.minecraft_ta.totalDebugCompanion.debugger;

import com.sun.jdi.ThreadReference;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Tracks adapter-visible evaluation state without clearing an active invocation. */
final class DebuggerEvaluationLifecycle {
    private final Map<Long, AtomicInteger> activeEvaluations = new ConcurrentHashMap<>();

    boolean isInEvaluation(ThreadReference thread) {
        return thread != null && this.activeEvaluations.containsKey(thread.uniqueID());
    }

    void begin(ThreadReference thread) {
        if (thread != null) {
            this.activeEvaluations.computeIfAbsent(thread.uniqueID(), ignored -> new AtomicInteger())
                    .incrementAndGet();
        }
    }

    void end(ThreadReference thread) {
        if (thread != null) {
            this.activeEvaluations.computeIfPresent(thread.uniqueID(), (ignored, count) ->
                    count.decrementAndGet() <= 0 ? null : count);
        }
    }

    void clearState(ThreadReference thread) {
        if (thread != null) {
            this.activeEvaluations.computeIfPresent(thread.uniqueID(), (ignored, count) ->
                    count.get() > 0 ? count : null);
        }
    }

    boolean isInEvaluation(long threadId) {
        return this.activeEvaluations.containsKey(threadId);
    }

    void begin(long threadId) {
        this.activeEvaluations.computeIfAbsent(threadId, ignored -> new AtomicInteger()).incrementAndGet();
    }

    void end(long threadId) {
        this.activeEvaluations.computeIfPresent(threadId, (ignored, count) ->
                count.decrementAndGet() <= 0 ? null : count);
    }

    void clearState(long threadId) {
        this.activeEvaluations.computeIfPresent(threadId, (ignored, count) ->
                count.get() > 0 ? count : null);
    }
}
