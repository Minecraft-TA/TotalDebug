package com.github.minecraft_ta.totaldebug.protocol.scnet;

import com.github.minecraft_ta.totaldebug.protocol.message.InspectSubjectPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.InspectSubjectPayload.ClassLink;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InspectSubjectMessageTest {
    @Test
    void roundTripsTheSubjectDescription() {
        InspectSubjectPayload payload = new InspectSubjectPayload(
                "game-session",
                "block minecraft:overworld 12 64 -3",
                "Furnace",
                "minecraft:furnace",
                "Minecraft",
                List.of(
                        new ClassLink("Block", "net.minecraft.world.level.block.FurnaceBlock"),
                        new ClassLink("Block entity", "net.minecraft.world.level.block.entity.FurnaceBlockEntity")
                )
        );
        ByteBufferOutputStream output = new ByteBufferOutputStream();
        new InspectSubjectMessage(payload).write(output);
        output.getBuffer().flip();

        InspectSubjectMessage read = new InspectSubjectMessage();
        read.read(new ByteBufferInputStream(output.getBuffer()));

        assertEquals(payload, read.payload());
    }

    @Test
    void rejectsAnInvalidSubjectOrTooManyClasses() {
        assertThrows(IllegalArgumentException.class, () -> new InspectSubjectPayload(
                "game-session", "block nowhere", "", "", "", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new InspectSubjectPayload(
                "game-session", "entity 00000000-0000-0000-0000-000000000001", "", "", "",
                Collections.nCopies(InspectSubjectPayload.MAX_CLASSES + 1, new ClassLink("Entity", "X"))));
    }
}
