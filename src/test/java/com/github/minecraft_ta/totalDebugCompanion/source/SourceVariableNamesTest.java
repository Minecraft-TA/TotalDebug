package com.github.minecraft_ta.totalDebugCompanion.source;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SourceVariableNamesTest {
    @Test
    void resolvesExactDisplayedNames() {
        SourceVariableNames names = SourceVariableNames.of(Map.of(
                "p_1_", "level",
                "var2", "state"
        ));

        assertEquals("level", names.displayedName("p_1_"));
        assertEquals("untouched", names.displayedName("untouched"));
    }
}
