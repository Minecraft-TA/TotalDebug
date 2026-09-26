package com.github.minecraft_ta.totaldebug.protocol.inspection;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SubjectRefTest {
    @Test
    void blockTextRoundTrips() {
        SubjectRef subject = SubjectRef.parse("block minecraft:the_nether -12 64 30000000");

        assertEquals(new SubjectRef.Block("minecraft:the_nether", -12, 64, 30_000_000), subject);
        assertEquals("block minecraft:the_nether -12 64 30000000", subject.format());
    }

    @Test
    void entityTextRoundTrips() {
        UUID uuid = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");

        SubjectRef subject = SubjectRef.parse("entity " + uuid);

        assertEquals(new SubjectRef.Entity(uuid), subject);
        assertEquals("entity " + uuid, subject.format());
    }

    @Test
    void modAndDefinitionTextRoundTrips() {
        assertEquals(new SubjectRef.Mod("mekanism"), SubjectRef.parse("mod mekanism"));
        assertEquals("mod mekanism", new SubjectRef.Mod("mekanism").format());
        SubjectRef.Definition definition = new SubjectRef.Definition(SubjectRef.DefinitionKind.ENTITY_TYPE, "minecraft:zombie");
        assertEquals("definition entity_type minecraft:zombie", definition.format());
        assertEquals(definition, SubjectRef.parse(definition.format()));
        assertEquals("minecraft", definition.namespace());
    }

    @Test
    void worldSubjectsRejectModsAndDefinitions() {
        assertEquals(new SubjectRef.Block("minecraft:overworld", 1, 2, 3), SubjectRef.parseWorld("block minecraft:overworld 1 2 3"));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> SubjectRef.parseWorld("definition item minecraft:stone"));
        assertEquals("definition item minecraft:stone names a mod or definition, not a block or entity in the world",
                failure.getMessage());
    }

    @Test
    void rejectsMalformedSubjects() {
        assertThrows(IllegalArgumentException.class, () -> SubjectRef.parse(""));
        assertThrows(IllegalArgumentException.class, () -> SubjectRef.parse("chunk minecraft:overworld 0 0"));
        assertThrows(IllegalArgumentException.class, () -> SubjectRef.parse("block minecraft:overworld 1 2"));
        assertThrows(IllegalArgumentException.class, () -> SubjectRef.parse("block Overworld 1 2 3"));
        assertThrows(IllegalArgumentException.class, () -> SubjectRef.parse("block minecraft:overworld 1 x 3"));
        assertThrows(IllegalArgumentException.class, () -> SubjectRef.parse("block minecraft:overworld 1 5000 3"));
        assertThrows(IllegalArgumentException.class, () -> SubjectRef.parse("entity not-a-uuid"));
        assertThrows(IllegalArgumentException.class, () -> SubjectRef.parse("block minecraft:" + "a".repeat(400) + " 0 0 0"));
        assertThrows(IllegalArgumentException.class, () -> SubjectRef.parse("mod Mekanism"));
        assertThrows(IllegalArgumentException.class, () -> SubjectRef.parse("mod"));
        assertThrows(IllegalArgumentException.class, () -> SubjectRef.parse("definition fluid minecraft:water"));
        assertThrows(IllegalArgumentException.class, () -> SubjectRef.parse("definition item stone"));
    }
}
