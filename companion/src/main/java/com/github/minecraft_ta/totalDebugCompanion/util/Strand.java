package com.github.minecraft_ta.totalDebugCompanion.util;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.Executor;

/**
 * Runs tasks one at a time and in the order they came, on shared threads: an owner's one serialized path
 * (docs/SYSTEMS.md, section 1). Two tasks of a strand never run at once, so what they change needs no lock among them.
 */
public final class Strand implements Executor {
    private static final System.Logger LOGGER = System.getLogger(Strand.class.getName());

    private final Executor threads;
    private final Queue<Runnable> tasks = new ArrayDeque<>();
    private boolean running;

    Strand(Executor threads) {
        this.threads = Objects.requireNonNull(threads, "threads");
    }

    @Override
    public void execute(Runnable task) {
        Objects.requireNonNull(task, "task");
        synchronized (this) {
            this.tasks.add(task);
            if (this.running) return;
            this.running = true;
        }
        this.threads.execute(this::drain);
    }

    private void drain() {
        while (true) {
            Runnable task;
            synchronized (this) {
                task = this.tasks.poll();
                if (task == null) {
                    this.running = false;
                    return;
                }
            }
            try {
                task.run();
            } catch (RuntimeException failure) {
                // One task's failure does not stop the ones after it.
                LOGGER.log(System.Logger.Level.WARNING, "A task failed on its strand", failure);
            }
        }
    }
}
