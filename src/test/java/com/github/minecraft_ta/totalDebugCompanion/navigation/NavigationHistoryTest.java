package com.github.minecraft_ta.totalDebugCompanion.navigation;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NavigationHistoryTest {
    @Test
    void traversesBothDirectionsAndClearsForwardOnlyForNewNavigation() {
        NavigationHistory history = new NavigationHistory(10);
        NavigationEntry first = local("First.java", 3);
        NavigationEntry second = local("Second.java", 7);

        history.recordNewNavigation(first);
        NavigationEntry back = history.destination(NavigationHistory.Direction.BACK, "runtime");
        assertEquals(first, back);
        history.complete(NavigationHistory.Direction.BACK, back, second);

        assertFalse(history.canNavigate(NavigationHistory.Direction.BACK, "runtime"));
        assertEquals(second, history.destination(NavigationHistory.Direction.FORWARD, "runtime"));

        history.recordNewNavigation(first);
        assertFalse(history.canNavigate(NavigationHistory.Direction.FORWARD, "runtime"));
        assertEquals(first, history.destination(NavigationHistory.Direction.BACK, "runtime"));
    }

    @Test
    void deduplicatesConsecutiveEntriesAndHonorsTheBound() {
        NavigationHistory history = new NavigationHistory(2);
        NavigationEntry first = local("First.java", 1);
        NavigationEntry second = local("Second.java", 2);
        NavigationEntry third = local("Third.java", 3);

        history.recordNewNavigation(first);
        history.recordNewNavigation(first);
        history.recordNewNavigation(second);
        history.recordNewNavigation(third);

        assertEquals(third, history.destination(NavigationHistory.Direction.BACK, "runtime"));
        history.complete(NavigationHistory.Direction.BACK, third, null);
        assertEquals(second, history.destination(NavigationHistory.Direction.BACK, "runtime"));
        history.complete(NavigationHistory.Direction.BACK, second, null);
        assertNull(history.destination(NavigationHistory.Direction.BACK, "runtime"));
    }

    @Test
    void prunesRuntimeEntriesFromAReplacedRuntimeButKeepsLocalFiles() {
        NavigationHistory history = new NavigationHistory(10);
        NavigationEntry local = local("Local.java", 0);
        NavigationEntry runtime = new NavigationEntry(
                new NavigationTarget.RuntimeClass("example.RuntimeType"),
                "old-runtime",
                NavigationViewState.EMPTY
        );

        history.recordNewNavigation(local);
        history.recordNewNavigation(runtime);

        assertTrue(history.canNavigate(NavigationHistory.Direction.BACK, "new-runtime"));
        assertEquals(local, history.destination(NavigationHistory.Direction.BACK, "new-runtime"));
    }

    private static NavigationEntry local(String fileName, int caret) {
        return new NavigationEntry(
                new NavigationTarget.LocalFile(Path.of(fileName)),
                null,
                new NavigationViewState(caret, 0, caret * 10)
        );
    }
}
