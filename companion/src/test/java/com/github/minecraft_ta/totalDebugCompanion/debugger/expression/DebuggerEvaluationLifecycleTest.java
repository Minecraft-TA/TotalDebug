package com.github.minecraft_ta.totalDebugCompanion.debugger.expression;

import com.sun.jdi.ThreadReference;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebuggerEvaluationLifecycleTest {
    @Test
    void clearStateDoesNotForgetAnActiveEvaluation() {
        DebuggerEvaluationLifecycle lifecycle = new DebuggerEvaluationLifecycle();
        ThreadReference thread = thread(41L);
        lifecycle.begin(thread);

        lifecycle.clearState(thread);

        assertTrue(lifecycle.isInEvaluation(thread));
        lifecycle.end(thread);
        assertFalse(lifecycle.isInEvaluation(thread));
    }

    @Test
    void nestedEvaluationStateSurvivesOneCompletionAndClear() {
        DebuggerEvaluationLifecycle lifecycle = new DebuggerEvaluationLifecycle();
        ThreadReference thread = thread(41L);
        lifecycle.begin(thread);
        lifecycle.begin(thread);
        lifecycle.end(thread);
        lifecycle.clearState(thread);

        assertTrue(lifecycle.isInEvaluation(thread));
        lifecycle.end(thread);
        assertFalse(lifecycle.isInEvaluation(thread));
    }

    private static ThreadReference thread(long id) {
        return (ThreadReference) Proxy.newProxyInstance(
                ThreadReference.class.getClassLoader(),
                new Class<?>[]{ThreadReference.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("uniqueID")) return id;
                    throw new UnsupportedOperationException(method.toString());
                }
        );
    }
}
