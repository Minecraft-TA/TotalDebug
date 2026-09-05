package com.github.minecraft_ta.totalDebugCompanion.debugger.expression;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class DebuggerEvaluationRunnerTest {
    @Test
    void callerTimeoutAndCancellationDoNotForgetTargetExecution() throws Exception {
        DebuggerEvaluationRunner runner = new DebuggerEvaluationRunner();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var operation = runner.start(null, () -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return "late result";
        });
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.TimeoutException.class,
                    () -> operation.completion().get(20, TimeUnit.MILLISECONDS));
            operation.cancel();
            assertSame(operation, runner.active());
            assertTrue(operation.running());
            assertThrows(IllegalStateException.class, () -> runner.run(null, () -> "overlap"));
        } finally { release.countDown(); }
        assertEquals("late result", operation.completion().get(2, TimeUnit.SECONDS));
        assertTrue(operation.cancellationRequested());
        assertNull(runner.active());
    }

    @Test
    void cancellationStopsAtNextCheckpoint() throws Exception {
        DebuggerEvaluationRunner runner = new DebuggerEvaluationRunner();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var operation = runner.start(null, () -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            DebuggerEvaluationRunner.checkpoint();
            return "must not execute";
        });
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        operation.cancel();
        release.countDown();
        assertThrows(Exception.class, () -> operation.completion().get(2, TimeUnit.SECONDS));
        assertEquals("cancelled", operation.snapshot().state());
    }
}
