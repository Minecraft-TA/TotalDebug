package com.github.minecraft_ta.totalDebugCompanion.ui.views.debugger;

import com.github.minecraft_ta.totalDebugCompanion.storage.InstanceState;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerValueLease;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DebuggerExpressionModelTest {
    private static final DebuggerExpressionModel.Outcome OUTCOME = DebuggerExpressionModel.Outcome.failure("fixture");

    @Test
    void replacingAnExpressionReleasesItsValuesAcrossCachedFrames() {
        var model = new DebuggerExpressionModel(InstanceState.inMemory());
        var released = new AtomicInteger();
        model.nextFrame("pause", 1);
        var key = model.beginExplicit("value");
        assertTrue(model.completionFor(key).apply(OUTCOME, released::incrementAndGet));
        model.nextFrame("pause", 2);
        model.beginExplicit("value");
        assertTrue(model.completionFor(key).apply(OUTCOME, released::incrementAndGet));
        model.beginExplicit("replacement");
        assertEquals(2, released.get());
        model.clearSession();
        assertEquals(2, released.get());
    }

    @Test
    void lateCompletionsReleaseTheirOwnershipAfterRemovalAndPauseExpiry() {
        var model = new DebuggerExpressionModel(InstanceState.inMemory());
        var released = new AtomicInteger();
        model.nextFrame("pause", 1);
        var key = model.beginExplicit("value");
        var removed = model.completionFor(key);
        model.remove(key);
        assertFalse(removed.apply(OUTCOME, released::incrementAndGet));
        model.beginExplicit("value");
        var expired = model.completionFor(key);
        model.nextFrame("new pause", 1);
        assertFalse(expired.apply(OUTCOME, released::incrementAndGet));
        assertEquals(2, released.get());
        assertNull(model.outcome(key));
    }

    @Test
    void anOlderCompletionCannotReplaceANewerRequestForTheSameExpression() {
        var model = new DebuggerExpressionModel(InstanceState.inMemory());
        var released = new AtomicInteger();
        model.nextFrame("pause", 1);
        var key = model.beginExplicit("value");
        var old = model.completionFor(key);
        model.beginExplicit("value");
        var current = model.completionFor(key);
        assertFalse(old.apply(OUTCOME, released::incrementAndGet));
        assertTrue(current.apply(OUTCOME, DebuggerValueLease.NONE));
        assertEquals(1, released.get());
        assertEquals(OUTCOME, model.outcome(key));
        model.clearSession();
    }
}
