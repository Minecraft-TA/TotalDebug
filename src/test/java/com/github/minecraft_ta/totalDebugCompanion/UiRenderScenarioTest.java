package com.github.minecraft_ta.totalDebugCompanion;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UiRenderScenarioTest {

    @Test
    void scenarioIdsAreUniqueAndRoundTrip() {
        var scenarios = Arrays.asList(UiRenderScenario.values());
        assertEquals(scenarios.size(), scenarios.stream().map(UiRenderScenario::id).distinct().count());
        for (UiRenderScenario scenario : scenarios) {
            assertEquals(scenario, UiRenderScenario.parse(scenario.id()));
        }
    }

    @Test
    void rejectsUnknownScenario() {
        assertThrows(IllegalArgumentException.class, () -> UiRenderScenario.parse("missing"));
    }
}
