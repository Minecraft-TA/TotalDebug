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
    void nestedFactsSurviveTheWireCodecAndStayBounded() {
        Fact tree = Fact.tree("Block entity", "3 entries", List.of(
                Fact.text("id", "\"minecraft:furnace\""),
                Fact.tree("Items", "1 item", List.of(Fact.tree("[0]", "2 entries", List.of(
                        Fact.text("id", "\"minecraft:coal\""), Fact.text("count", "8")), 2)), 1)
        ), 5);
        ExecutionResult result = new ExecutionResult(ExecutionStatus.RUN_COMPLETED, ExecutionText.empty(),
                null, ExecutionText.empty(), List.of(new FactSection("NBT", List.of(tree), 1)));

        ExecutionResult decoded = ExecutionResultCodec.decode(ExecutionResultCodec.encode(result).json());

        assertEquals(tree, decoded.facts().getFirst().facts().getFirst());
        assertEquals(3, tree.omittedChildren());
        assertEquals(6, tree.nodeCount());
        assertThrows(IllegalArgumentException.class, () -> new FactSection("Deep", List.of(nested(FactSection.MAX_DEPTH + 1)), 1));
        assertThrows(IllegalArgumentException.class, () -> new FactSection("Wide", List.of(Fact.tree("root", "",
                Collections.nCopies(FactSection.MAX_NODES, Fact.text("a", "b")), FactSection.MAX_NODES)), 1));
        assertThrows(IllegalArgumentException.class, () -> new Fact(Fact.Kind.BAR, "Stored", "", "", 1, 2, "FE",
                List.of(Fact.text("a", "b")), 1));
    }

    private static Fact nested(int depth) {
        return depth == 1 ? Fact.text("leaf", "") : Fact.tree("node", "", List.of(nested(depth - 1)), 1);
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
