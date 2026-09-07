package com.github.minecraft_ta.totaldebug.tick;

import com.github.minecraft_ta.totaldebug.TotalDebug;

import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;

/**
 * Schedules work for a specific logical tick stream and phase.
 *
 * <p>A drain operates on a snapshot. Tasks submitted while that snapshot is
 * running are held for the next matching tick, preventing recursive scheduling
 * from extending the current tick without bound.</p>
 */
public final class TickTaskScheduler {
    private final Map<TickDomain, Map<TickPhase, TaskQueue>> queues = new EnumMap<>(TickDomain.class);
    private final TaskFailureHandler failureHandler;

    public TickTaskScheduler() {
        this((domain, phase, throwable) ->
                TotalDebug.LOGGER.error("A {} {} tick task failed", domain, phase, throwable));
    }

    TickTaskScheduler(TaskFailureHandler failureHandler) {
        this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");

        for (TickDomain domain : TickDomain.values()) {
            Map<TickPhase, TaskQueue> phaseQueues = new EnumMap<>(TickPhase.class);
            for (TickPhase phase : TickPhase.values()) {
                phaseQueues.put(phase, new TaskQueue());
            }
            this.queues.put(domain, phaseQueues);
        }
    }

    public void submit(TickDomain domain, TickPhase phase, Runnable task) {
        queue(domain, phase).submit(Objects.requireNonNull(task, "task"));
    }

    public int drain(TickDomain domain, TickPhase phase) {
        Queue<Runnable> snapshot = queue(domain, phase).takeSnapshot();
        int executed = 0;

        Runnable task;
        while ((task = snapshot.poll()) != null) {
            try {
                task.run();
            } catch (Throwable throwable) {
                this.failureHandler.onFailure(domain, phase, throwable);
            }
            executed++;
        }

        return executed;
    }

    public int pendingTasks(TickDomain domain, TickPhase phase) {
        return queue(domain, phase).size();
    }

    public void clear(TickDomain domain) {
        for (TickPhase phase : TickPhase.values()) {
            queue(domain, phase).clear();
        }
    }

    private TaskQueue queue(TickDomain domain, TickPhase phase) {
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(phase, "phase");
        return this.queues.get(domain).get(phase);
    }

    @FunctionalInterface
    interface TaskFailureHandler {
        void onFailure(TickDomain domain, TickPhase phase, Throwable throwable);
    }

    private static final class TaskQueue {
        private Queue<Runnable> tasks = new ArrayDeque<>();

        synchronized void submit(Runnable task) {
            this.tasks.add(task);
        }

        synchronized Queue<Runnable> takeSnapshot() {
            Queue<Runnable> snapshot = this.tasks;
            this.tasks = new ArrayDeque<>();
            return snapshot;
        }

        synchronized int size() {
            return this.tasks.size();
        }

        synchronized void clear() {
            this.tasks = new ArrayDeque<>();
        }
    }
}
