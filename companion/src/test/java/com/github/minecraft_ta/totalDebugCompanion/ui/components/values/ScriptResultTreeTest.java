package com.github.minecraft_ta.totalDebugCompanion.ui.components.values;

import com.github.minecraft_ta.totalDebugCompanion.script.ExecutionText;
import com.github.minecraft_ta.totalDebugCompanion.script.ExecutionValue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptResultTreeTest {
    @Test
    void boundsSearchTextWithoutChangingTheCanonicalValue() {
        String value = "x".repeat(1_000_000);
        ExecutionValue snapshot = new ExecutionValue(
                text("java.lang.String"),
                text(value),
                text(""),
                ExecutionValue.Kind.STRING,
                0,
                0,
                false,
                List.of()
        );

        String searchText = ScriptResultTree.boundedSearchText("result", snapshot);

        assertEquals(2_048, searchText.length());
        assertTrue(searchText.startsWith("result java.lang.String "));
        assertEquals(value, snapshot.value().text());
    }

    private static ExecutionText text(String value) {
        return new ExecutionText(value, value.length(), false);
    }
}
