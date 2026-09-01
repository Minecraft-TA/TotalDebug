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
    void persistsOnlyTheBoundedMostRecentUniqueExpressions() {
        Path file = this.temporaryDirectory.resolve("history.json");
        ExpressionHistory history = new ExpressionHistory(file);
        for (int index = 0; index < ExpressionHistory.MAX_ENTRIES + 5; index++) {
            history.record(entry("value" + index));
        }
        history.record(entry("value10"));

        List<ExpressionHistory.Entry> restored = new ExpressionHistory(file).entries();

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
