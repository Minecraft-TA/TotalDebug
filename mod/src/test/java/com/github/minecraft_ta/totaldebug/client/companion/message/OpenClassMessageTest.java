package com.github.minecraft_ta.totaldebug.client.companion.message;

import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OpenClassMessageTest {
    @Test
    void preservesTheExistingCompanionWireFormat() {
        OpenClassMessage outgoing = new OpenClassMessage("net.minecraft.world.level.block.Block", 9, "tick()V");
        ByteBufferOutputStream output = new ByteBufferOutputStream();
        outgoing.write(output);
        output.getBuffer().flip();

        OpenClassMessage incoming = new OpenClassMessage();
        incoming.read(new ByteBufferInputStream(output.getBuffer()));

        assertEquals("net.minecraft.world.level.block.Block", incoming.binaryName());
        assertEquals(9, incoming.targetType());
        assertEquals("tick()V", incoming.targetIdentifier());
    }
}
