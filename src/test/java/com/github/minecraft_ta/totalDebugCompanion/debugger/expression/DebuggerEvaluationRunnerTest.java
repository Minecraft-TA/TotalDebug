package com.github.minecraft_ta.totalDebugCompanion.debugger.expression;

import com.microsoft.java.debug.core.IDebugSession;
import com.microsoft.java.debug.core.adapter.IDebugAdapterContext;
import com.sun.jdi.ThreadReference;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebuggerEvaluationRunnerTest {
    @Test
    void detachesTheSessionAndFailsWhenAnInvocationDoesNotReturn() throws Exception {
        CountDownLatch detached = new CountDownLatch(1);
        AtomicInteger detachCalls = new AtomicInteger();
        IDebugSession session = proxy(IDebugSession.class, (method, arguments) -> {
            if (method.getName().equals("detach")) {
                detachCalls.incrementAndGet();
                detached.countDown();
                return null;
            }
            throw new UnsupportedOperationException(method.toString());
        });
        IDebugAdapterContext context = proxy(IDebugAdapterContext.class, (method, arguments) -> {
            if (method.getName().equals("getDebugSession")) return session;
            throw new UnsupportedOperationException(method.toString());
        });
        ThreadReference thread = thread(11L);
        DebuggerEvaluationRunner runner = new DebuggerEvaluationRunner(() -> context, Duration.ofMillis(50));

        try {
            runner.run(thread, () -> {
                assertTrue(detached.await(2, TimeUnit.SECONDS));
                return "late result";
            }).get(2, TimeUnit.SECONDS);
            throw new AssertionError("Timed-out evaluation unexpectedly completed");
        } catch (ExecutionException expected) {
            assertInstanceOf(java.util.concurrent.TimeoutException.class, expected.getCause());
        }
        assertEquals(1, detachCalls.get());
    }

    @Test
    void completedEvaluationCancelsItsTimeout() throws Exception {
        AtomicInteger detachCalls = new AtomicInteger();
        IDebugSession session = proxy(IDebugSession.class, (method, arguments) -> {
            if (method.getName().equals("detach")) {
                detachCalls.incrementAndGet();
                return null;
            }
            throw new UnsupportedOperationException(method.toString());
        });
        IDebugAdapterContext context = proxy(IDebugAdapterContext.class, (method, arguments) -> {
            if (method.getName().equals("getDebugSession")) return session;
            throw new UnsupportedOperationException(method.toString());
        });
        DebuggerEvaluationRunner runner = new DebuggerEvaluationRunner(() -> context, Duration.ofMillis(50));

        assertEquals("value", runner.run(thread(12L), () -> "value").get(2, TimeUnit.SECONDS));
        Thread.sleep(100);
        assertEquals(0, detachCalls.get());
    }

    private static ThreadReference thread(long id) {
        return proxy(ThreadReference.class, (method, arguments) -> {
            if (method.getName().equals("uniqueID")) return id;
            throw new UnsupportedOperationException(method.toString());
        });
    }

    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return type.cast(Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, arguments) -> invocation.invoke(method, arguments)
        ));
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(java.lang.reflect.Method method, Object[] arguments) throws Throwable;
    }
}
