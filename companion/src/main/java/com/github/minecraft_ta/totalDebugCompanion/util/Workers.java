package com.github.minecraft_ta.totalDebugCompanion.util;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The threads Companion's owners and pages share (docs/SYSTEMS.md, section 4): a bounded pool for file work, one for
 * owners' strands, and one timer that only hands work on. An owner's serial path is a {@link Strand} over the owners'
 * pool, where no task waits for another owner's.
 */
public final class Workers {
    private static final AtomicInteger FILE_THREADS = new AtomicInteger();
    private static final AtomicInteger OWNER_THREADS = new AtomicInteger();
    private static final ExecutorService FILES = Executors.newFixedThreadPool(4, task -> Thread.ofPlatform()
            .daemon()
            .name("Companion files " + FILE_THREADS.incrementAndGet())
            .unstarted(task));
    /**
     * The threads owners' strands run on, apart from the file work: a page's read that waits for an owner's first value
     * never takes the thread that value needs.
     */
    private static final ExecutorService OWNERS = Executors.newFixedThreadPool(4, task -> Thread.ofPlatform()
            .daemon()
            .name("Companion owners " + OWNER_THREADS.incrementAndGet())
            .unstarted(task));
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(task -> Thread.ofPlatform()
            .daemon()
            .name("Companion timer")
            .unstarted(task));

    private Workers() {
    }

    /** Where files, zips and the like are read: platform threads, since a virtual one is pinned while it reads a zip. */
    public static Executor files() {
        return FILES;
    }

    /** A new serial path, for an owner whose state changes in one order. */
    public static Strand strand() {
        return new Strand(OWNERS);
    }

    /** Hands {@code task} to {@code target} after {@code millis}; the timer itself runs nothing else. */
    public static ScheduledFuture<?> later(long millis, Executor target, Runnable task) {
        return TIMER.schedule(() -> target.execute(task), millis, TimeUnit.MILLISECONDS);
    }
}
