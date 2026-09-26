package com.github.minecraft_ta.totaldebug.protocol.scnet;

import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectIdentity;
import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectIdentity.ClassLink;
import com.github.minecraft_ta.totaldebug.protocol.message.InspectSubjectPayload;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InspectSubjectMessageTest {
    private static final SubjectIdentity FURNACE = new SubjectIdentity(
            SubjectIdentity.Kind.BLOCK,
            "minecraft:furnace",
            "Furnace",
            "Minecraft",
            List.of(
                    new ClassLink("Block", "net.minecraft.world.level.block.FurnaceBlock"),
                    new ClassLink("Block entity", "net.minecraft.world.level.block.entity.FurnaceBlockEntity")
            ),
            "minecraft:furnace"
    );

    @Test
    void roundTripsTheSubjectDescription() {
        InspectSubjectPayload payload = new InspectSubjectPayload(
                "game-session",
                "block minecraft:overworld 12 64 -3",
                FURNACE,
                "minecraft:item/furnace",
                Map.of(0, 0xFF48B518)
        );
        ByteBufferOutputStream output = new ByteBufferOutputStream();
        new InspectSubjectMessage(payload).write(output);
        output.getBuffer().flip();

        InspectSubjectMessage read = new InspectSubjectMessage();
        read.read(new ByteBufferInputStream(output.getBuffer()));

        assertEquals(payload, read.payload());
    }

    @Test
    void rejectsSomethingNotInTheGameOrTooManyClasses() {
        assertThrows(IllegalArgumentException.class, () -> new InspectSubjectPayload(
                "game-session", "block nowhere", FURNACE, "", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new InspectSubjectPayload(
                "game-session", "definition item minecraft:furnace", FURNACE, "", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new SubjectIdentity(SubjectIdentity.Kind.ENTITY,
                "minecraft:pig", "", "",
                Collections.nCopies(SubjectIdentity.MAX_CLASSES + 1, new ClassLink("Entity", "X")), ""));
    }

    @Test
    void resourceSnapshotRoundTripsAndRejectsAnEmptyArchive() {
        ByteBufferOutputStream output = new ByteBufferOutputStream();
        new ResourceSnapshotMessage("C:/instance/total-debug/cache/previews/a.zip", 3).write(output);
        output.getBuffer().flip();

        ResourceSnapshotMessage read = new ResourceSnapshotMessage();
        read.read(new ByteBufferInputStream(output.getBuffer()));

        assertEquals("C:/instance/total-debug/cache/previews/a.zip", read.archive());
        assertEquals(3, read.layers());
        assertThrows(IllegalArgumentException.class, () -> new ResourceSnapshotMessage("", 1));
    }
}
