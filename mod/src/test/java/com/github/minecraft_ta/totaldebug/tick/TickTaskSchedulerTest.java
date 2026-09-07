package com.github.minecraft_ta.totaldebug.tick;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class TickTaskSchedulerTest {
    @Test
    void drainsOnlyTheSelectedDomainAndPhase() {
        TickTaskScheduler scheduler = schedulerIgnoringFailures();
        List<String> executed = new ArrayList<>();

        scheduler.submit(TickDomain.CLIENT, TickPhase.PRE, () -> executed.add("client-pre"));
        scheduler.submit(TickDomain.CLIENT, TickPhase.POST, () -> executed.add("client-post"));
        scheduler.submit(TickDomain.SERVER, TickPhase.PRE, () -> executed.add("server-pre"));

        assertEquals(1, scheduler.drain(TickDomain.CLIENT, TickPhase.PRE));
        assertEquals(List.of("client-pre"), executed);
        assertEquals(1, scheduler.pendingTasks(TickDomain.CLIENT, TickPhase.POST));
        assertEquals(1, scheduler.pendingTasks(TickDomain.SERVER, TickPhase.PRE));
    }

    @Test
    void defersTasksSubmittedDuringDrainUntilTheNextMatchingTick() {
        TickTaskScheduler scheduler = schedulerIgnoringFailures();
        List<String> executed = new ArrayList<>();

        scheduler.submit(TickDomain.SERVER, TickPhase.PRE, () -> {
            executed.add("first");
            scheduler.submit(TickDomain.SERVER, TickPhase.PRE, () -> executed.add("deferred"));
        });

        assertEquals(1, scheduler.drain(TickDomain.SERVER, TickPhase.PRE));
        assertEquals(List.of("first"), executed);
        assertEquals(1, scheduler.pendingTasks(TickDomain.SERVER, TickPhase.PRE));

        assertEquals(1, scheduler.drain(TickDomain.SERVER, TickPhase.PRE));
        assertEquals(List.of("first", "deferred"), executed);
    }

    @Test
    void isolatesFailuresAndContinuesTheSnapshot() {
        List<Throwable> failures = new ArrayList<>();
        TickTaskScheduler scheduler = new TickTaskScheduler((domain, phase, throwable) -> failures.add(throwable));
        AtomicInteger executions = new AtomicInteger();
        RuntimeException failure = new RuntimeException("expected");

        scheduler.submit(TickDomain.CLIENT, TickPhase.POST, () -> {
            throw failure;
        });
        scheduler.submit(TickDomain.CLIENT, TickPhase.POST, executions::incrementAndGet);

        assertEquals(2, scheduler.drain(TickDomain.CLIENT, TickPhase.POST));
        assertEquals(1, executions.get());
        assertEquals(1, failures.size());
        assertSame(failure, failures.getFirst());
    }

    @Test
    void clearsOneDomainWithoutDiscardingTheOther() {
        TickTaskScheduler scheduler = schedulerIgnoringFailures();
        scheduler.submit(TickDomain.CLIENT, TickPhase.PRE, () -> { });
        scheduler.submit(TickDomain.SERVER, TickPhase.PRE, () -> { });

        scheduler.clear(TickDomain.CLIENT);

        assertEquals(0, scheduler.pendingTasks(TickDomain.CLIENT, TickPhase.PRE));
        assertEquals(1, scheduler.pendingTasks(TickDomain.SERVER, TickPhase.PRE));
    }

    private static TickTaskScheduler schedulerIgnoringFailures() {
        return new TickTaskScheduler((domain, phase, throwable) -> { });
    }
}
