package com.github.minecraft_ta.totaldebug.protocol.execution;

import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectIdentity;
import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectRef;
import com.github.minecraft_ta.totaldebug.protocol.nbt.NbtData;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
                null, ExecutionText.empty(), facts, null);

        ExecutionResult decoded = ExecutionResultCodec.decode(ExecutionResultCodec.encode(result).json());

        assertEquals(facts, decoded.facts());
        assertEquals(25, decoded.facts().get(1).omittedFacts());
    }

    @Test
    void identityAndProblemsSurviveTheWireCodec() {
        SubjectIdentity identity = new SubjectIdentity(SubjectIdentity.Kind.BLOCK, "minecraft:chest", "Chest",
                "Minecraft", List.of(new SubjectIdentity.ClassLink("Block", "net.minecraft.world.level.block.ChestBlock")),
                "minecraft:chest");
        List<FactSection> facts = List.of(new FactSection("Storage", List.of(
                Fact.text("Contents", "Empty"),
                Fact.problem("Fluids", "java.lang.IllegalStateException: broken tank")), 2));
        ExecutionResult result = ExecutionResult.failed("", null, "boom").withFacts(facts).withIdentity(identity);

        ExecutionResult decoded = ExecutionResultCodec.decode(ExecutionResultCodec.encode(result).json());

        assertEquals(identity, decoded.identity());
        assertEquals(facts, decoded.facts());
        assertEquals(ExecutionStatus.RUN_EXCEPTION, decoded.status());
    }

    @Test
    void resultsWithoutFactsDecodeWithAnEmptyList() {
        ExecutionResult decoded = ExecutionResultCodec.decode(
                "{\"status\":\"RUN_COMPLETED\",\"logs\":{\"text\":\"\",\"totalCharacters\":0,\"truncated\":false},"
                        + "\"error\":{\"text\":\"\",\"totalCharacters\":0,\"truncated\":false}}");

        assertEquals(List.of(), decoded.facts());
        assertEquals(null, decoded.identity());
    }

    @Test
    void dataFactsSurviveTheWireCodecExactly() {
        byte[] nbt = {10, 3, 0, 5, 'C', 'o', 'u', 'n', 't', 0, 0, 0, 7, 0};
        Fact data = Fact.data("Block entity", FactData.of(nbt, List.of(new FactData.Omission("Items", 3))));
        ExecutionResult result = new ExecutionResult(ExecutionStatus.RUN_COMPLETED, ExecutionText.empty(),
                null, ExecutionText.empty(), List.of(new FactSection("NBT", List.of(data), 1)), null);

        ExecutionResult decoded = ExecutionResultCodec.decode(ExecutionResultCodec.encode(result).json());

        FactData carried = decoded.facts().getFirst().facts().getFirst().data();
        assertArrayEquals(nbt, carried.bytes());
        assertEquals(nbt.length, carried.size());
        assertEquals("{Count:7}", NbtData.snbt(carried.tag()));
        assertEquals(List.of(new FactData.Omission("Items", 3)), carried.omissions());
        assertThrows(IllegalArgumentException.class, () -> new Fact(Fact.Kind.TEXT, "a", "", "", 0, 0, "",
                FactData.of(nbt, List.of()), null, null));
        assertThrows(IllegalArgumentException.class, () -> new Fact(Fact.Kind.DATA, "a", "", "", 0, 0, ""));
    }

    @Test
    void linksSurviveTheWireCodec() {
        Fact handler = Fact.text("neoforge:item_handler", "SidedInvWrapper")
                .withLink(FactLink.toClass("net.neoforged.neoforge.items.wrapper.SidedInvWrapper"));
        ExecutionResult result = ExecutionResult.completed("", null)
                .withFacts(List.of(new FactSection("Capabilities", List.of(handler), 1)));

        ExecutionResult decoded = ExecutionResultCodec.decode(ExecutionResultCodec.encode(result).json());

        assertEquals(handler, decoded.facts().getFirst().facts().getFirst());
        assertThrows(IllegalArgumentException.class, () -> FactLink.toClass(" "));
        assertEquals(new FactLink(FactLink.Kind.SUBJECT, "mod mekanism"), FactLink.toSubject(new SubjectRef.Mod("mekanism")));
    }

    @Test
    void slotsReachedThroughASideSurviveTheWireCodec() {
        Fact top = Fact.stack("Top", "minecraft:raw_iron", 5, "Raw Iron").withTransfer(new Fact.Transfer(true, null));
        ExecutionResult result = ExecutionResult.completed("", null)
                .withFacts(List.of(new FactSection("Items", List.of(top), 1)));

        ExecutionResult decoded = ExecutionResultCodec.decode(ExecutionResultCodec.encode(result).json());

        assertEquals(top, decoded.facts().getFirst().facts().getFirst());
        assertThrows(IllegalArgumentException.class, () -> Fact.text("Top", "Coal").withTransfer(new Fact.Transfer(true, true)));
    }

    @Test
    void oneResultCarriesABoundedAmountOfData() {
        FactData full = FactData.of(new byte[FactData.MAX_BYTES], List.of());
        List<Fact> facts = Collections.nCopies(FactData.MAX_TOTAL_BYTES / FactData.MAX_BYTES + 1,
                Fact.data("copy", full));

        assertThrows(IllegalArgumentException.class, () -> ExecutionResult.completed("", null)
                .withFacts(List.of(new FactSection("NBT", facts, facts.size()))));
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
