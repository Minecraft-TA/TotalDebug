package com.github.minecraft_ta.totaldebug.script;

import com.github.minecraft_ta.totaldebug.protocol.execution.Fact;
import com.github.minecraft_ta.totaldebug.protocol.execution.FactData;
import com.github.minecraft_ta.totaldebug.protocol.execution.FactLink;
import com.github.minecraft_ta.totaldebug.protocol.execution.FactSection;
import com.github.minecraft_ta.totaldebug.protocol.nbt.NbtData;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptFactsTest {
    @Test
    void nbtIsReportedAsExactData() {
        CompoundTag root = NbtFactDataTest.corpus();
        ScriptFacts facts = new ScriptFacts(text -> { });

        facts.section("NBT").nbt("Block entity", root);

        Fact fact = facts.snapshot().getFirst().facts().getFirst();
        assertEquals(Fact.Kind.DATA, fact.kind());
        assertEquals("Block entity", fact.label());
        assertTrue(fact.data().complete());
        assertEquals(root.toString(), NbtData.snbt(fact.data().tag()));
    }

    @Test
    void classesAreReportedByNameAndLinkToTheirSource() {
        ScriptFacts facts = new ScriptFacts(text -> { });

        facts.section("Capabilities").classLink("neoforge:item_handler", ArrayList.class)
                .classLink("anonymous", new Object() { }.getClass());

        List<Fact> reported = facts.snapshot().getFirst().facts();
        assertEquals(Fact.text("neoforge:item_handler", "ArrayList")
                .withLink(FactLink.toClass("java.util.ArrayList")), reported.get(0));
        assertEquals("ScriptFactsTest$1", reported.get(1).value());
        assertEquals(ScriptFactsTest.class.getName() + "$1", reported.get(1).link().target());
    }

    @Test
    void dataFactsShareOneBudgetPerRead() {
        CompoundTag large = new CompoundTag();
        large.put("bytes", new ByteArrayTag(new byte[FactData.MAX_BYTES - 64]));
        ScriptFacts facts = new ScriptFacts(text -> { });

        for (int index = 0; index < 5; index++) {
            facts.section("NBT").nbt("Copy " + index, large);
        }

        List<Fact> reported = facts.snapshot().getFirst().facts();
        long total = reported.stream().filter(fact -> fact.data() != null).mapToLong(fact -> fact.data().size()).sum();
        assertTrue(total <= FactData.MAX_TOTAL_BYTES, "reported " + total);
        assertFalse(reported.getLast().data() != null && reported.getLast().data().complete(),
                "the fifth copy cannot fit completely");
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
