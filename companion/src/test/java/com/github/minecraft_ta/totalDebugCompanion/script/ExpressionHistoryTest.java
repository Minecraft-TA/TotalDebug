package com.github.minecraft_ta.totalDebugCompanion.script;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExpressionHistoryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsOnlyTheBoundedMostRecentUniqueExpressions() throws Exception {
        var paths = new com.github.minecraft_ta.totaldebug.storage.InstancePaths(this.temporaryDirectory);
        var state = com.github.minecraft_ta.totalDebugCompanion.storage.InstanceState.open(paths);
        ExpressionHistory history = state.expressionHistory();
        for (int index = 0; index < ExpressionHistory.MAX_ENTRIES + 5; index++) {
            history.record(entry("value" + index));
        }
        history.record(entry("value10"));

        state.close();
        var reopened = com.github.minecraft_ta.totalDebugCompanion.storage.InstanceState.open(paths);
        List<ExpressionHistory.Entry> restored = reopened.expressionHistory().entries();
        reopened.close();

        assertEquals(ExpressionHistory.MAX_ENTRIES, restored.size());
        assertEquals("value10", restored.getFirst().expression());
        assertEquals(1, restored.stream().filter(entry -> entry.expression().equals("value10")).count());
        assertEquals(List.of("java.util.List"), restored.getFirst().imports());
    }

    private static ExpressionHistory.Entry entry(String expression) {
        return new ExpressionHistory.Entry(
                expression,
                SnippetExecutionService.Side.SERVER,
                List.of("java.util.List")
        );
    }
}
