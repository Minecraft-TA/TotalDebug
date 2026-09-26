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
    void rejectsMalformedSubjects() {
        assertThrows(IllegalArgumentException.class, () -> SubjectRef.parse(""));
        assertThrows(IllegalArgumentException.class, () -> SubjectRef.parse("chunk minecraft:overworld 0 0"));
        assertThrows(IllegalArgumentException.class, () -> SubjectRef.parse("block minecraft:overworld 1 2"));
        assertThrows(IllegalArgumentException.class, () -> SubjectRef.parse("block Overworld 1 2 3"));
        assertThrows(IllegalArgumentException.class, () -> SubjectRef.parse("block minecraft:overworld 1 x 3"));
        assertThrows(IllegalArgumentException.class, () -> SubjectRef.parse("block minecraft:overworld 1 5000 3"));
        assertThrows(IllegalArgumentException.class, () -> SubjectRef.parse("entity not-a-uuid"));
        assertThrows(IllegalArgumentException.class, () -> SubjectRef.parse("block minecraft:" + "a".repeat(400) + " 0 0 0"));
    }
}
