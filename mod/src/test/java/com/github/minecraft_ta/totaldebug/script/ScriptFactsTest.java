package com.github.minecraft_ta.totaldebug.script;

import com.github.minecraft_ta.totaldebug.protocol.execution.Fact;
import com.github.minecraft_ta.totaldebug.protocol.execution.FactSection;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptFactsTest {
    @Test
    void nbtBecomesASortedTreeWithSnbtLeaves() {
        CompoundTag item = new CompoundTag();
        item.putString("id", "minecraft:coal");
        item.putByte("count", (byte) 8);
        ListTag items = new ListTag();
        items.add(item);
        CompoundTag root = new CompoundTag();
        root.put("Items", items);
        root.putShort("BurnTime", (short) 120);

        ScriptFacts facts = new ScriptFacts(text -> { });
        facts.section("NBT").nbt("Block entity", root);
        Fact tree = facts.snapshot().getFirst().facts().getFirst();

        assertEquals("2 entries", tree.value());
        assertEquals(List.of("BurnTime", "Items"), tree.children().stream().map(Fact::label).toList());
        assertEquals("120s", tree.children().get(0).value());
        Fact first = tree.children().get(1).children().getFirst();
        assertEquals("[0]", first.label());
        assertEquals(List.of(Fact.text("count", "8b"), Fact.text("id", "\"minecraft:coal\"")), first.children());
    }

    @Test
    void largeTagsStayWithinTheSectionBudgetAndCountWhatWasLeftOut() {
        ListTag values = new ListTag();
        for (int index = 0; index < 5_000; index++) {
            values.add(IntTag.valueOf(index));
        }
        CompoundTag nested = new CompoundTag();
        CompoundTag current = nested;
        for (int depth = 0; depth < 40; depth++) {
            CompoundTag next = new CompoundTag();
            current.put("child", next);
            current = next;
        }
        current.put("leaf", StringTag.valueOf("deep"));

        ScriptFacts facts = new ScriptFacts(text -> { });
        facts.section("NBT").nbt("Values", values).nbt("Nested", nested);
        FactSection section = facts.snapshot().getFirst();

        int nodes = section.facts().stream().mapToInt(Fact::nodeCount).sum();
        assertTrue(nodes <= FactSection.MAX_NODES, "retained " + nodes);
        Fact list = section.facts().getFirst();
        assertEquals(5_000, list.totalChildren());
        assertTrue(list.omittedChildren() > 0);
    }

    @Test
    void aFailingGuardedReadKeepsEarlierFactsReportsTheProblemAndLetsLaterReadsRun() {
        List<String> log = new ArrayList<>();
        ScriptFacts facts = new ScriptFacts(log::add);

        facts.guarded("Items", () -> facts.section("Items").text("Slots", 3));
        facts.guarded("Fluids", () -> {
            facts.section("Fluids").text("Tanks", 2);
            throw new IllegalStateException("broken tank");
        });
        facts.guarded("Energy", () -> facts.section("Energy").text("Stored", 10));

        List<FactSection> sections = facts.snapshot();
        assertEquals(List.of("Items", "Fluids", "Energy"), sections.stream().map(FactSection::title).toList());
        assertEquals(List.of(Fact.text("Tanks", "2"),
                        Fact.problem("Read failed", "IllegalStateException: broken tank")),
                sections.get(1).facts());
        assertEquals(1, log.size());
        assertTrue(log.getFirst().startsWith("Fluids could not be read:"), log.getFirst());
        assertTrue(log.getFirst().contains("broken tank"), log.getFirst());
        assertFalse(sections.get(2).facts().isEmpty());
    }
}
