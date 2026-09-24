package com.github.minecraft_ta.totaldebug.protocol.execution;

import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExecutionFactsTest {
    @Test
    void factsSurviveTheWireCodec() {
        List<FactSection> facts = List.of(
                new FactSection("Energy", List.of(Fact.bar("Stored", 1_200, 50_000, "FE")), 1),
                new FactSection("Items", List.of(
                        Fact.stack("Slot 0", "minecraft:iron_ingot", 12, "Iron Ingot"),
                        Fact.stack("Slot 1", "", 0, "")
                ), 27)
        );
        ExecutionResult result = new ExecutionResult(ExecutionStatus.RUN_COMPLETED, ExecutionText.complete("log"),
                null, ExecutionText.empty(), facts);

        ExecutionResult decoded = ExecutionResultCodec.decode(ExecutionResultCodec.encode(result).json());

        assertEquals(facts, decoded.facts());
        assertEquals(25, decoded.facts().get(1).omittedFacts());
    }

    @Test
    void resultsWithoutFactsDecodeWithAnEmptyList() {
        ExecutionResult decoded = ExecutionResultCodec.decode(
                "{\"status\":\"RUN_COMPLETED\",\"logs\":{\"text\":\"\",\"totalCharacters\":0,\"truncated\":false},"
                        + "\"error\":{\"text\":\"\",\"totalCharacters\":0,\"truncated\":false}}");

        assertEquals(List.of(), decoded.facts());
    }

    @Test
    void rejectsUnboundedFacts() {
        assertThrows(IllegalArgumentException.class, () -> Fact.text("x".repeat(Fact.MAX_TEXT_LENGTH + 1), ""));
        assertThrows(IllegalArgumentException.class, () -> Fact.bar("Stored", -1, 10, "FE"));
        assertThrows(IllegalArgumentException.class, () -> new FactSection("Items",
                Collections.nCopies(FactSection.MAX_FACTS + 1, Fact.text("a", "b")), FactSection.MAX_FACTS + 1));
        assertEquals(Fact.MAX_TEXT_LENGTH, Fact.clip("y".repeat(1_000)).length());
    }
}
