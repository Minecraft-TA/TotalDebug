package com.github.minecraft_ta.totalDebugCompanion.debugger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebuggerEvaluationLifecycleTest {
    @Test
    void clearStateDoesNotForgetAnActiveEvaluation() {
        DebuggerEvaluationLifecycle lifecycle = new DebuggerEvaluationLifecycle();
        lifecycle.begin(41L);

        lifecycle.clearState(41L);

        assertTrue(lifecycle.isInEvaluation(41L));
        lifecycle.end(41L);
        assertFalse(lifecycle.isInEvaluation(41L));
    }

    @Test
    void nestedEvaluationStateSurvivesOneCompletionAndClear() {
        DebuggerEvaluationLifecycle lifecycle = new DebuggerEvaluationLifecycle();
        lifecycle.begin(41L);
        lifecycle.begin(41L);
        lifecycle.end(41L);
        lifecycle.clearState(41L);

        assertTrue(lifecycle.isInEvaluation(41L));
        lifecycle.end(41L);
        assertFalse(lifecycle.isInEvaluation(41L));
    }
}
