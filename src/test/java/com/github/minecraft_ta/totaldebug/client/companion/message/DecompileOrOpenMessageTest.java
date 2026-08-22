package com.github.minecraft_ta.totaldebug.client.companion.message;

import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DecompileOrOpenMessageTest {
    @Test
    void preservesTheExistingCompanionWireFormat() {
        DecompileOrOpenMessage outgoing = new DecompileOrOpenMessage("C:/code/Block.java", 9, "tick()V");
        ByteBufferOutputStream output = new ByteBufferOutputStream();
        outgoing.write(output);
        output.getBuffer().flip();

        DecompileOrOpenMessage incoming = new DecompileOrOpenMessage();
        incoming.read(new ByteBufferInputStream(output.getBuffer()));

        assertEquals("C:/code/Block.java", incoming.name());
        assertEquals(9, incoming.targetType());
        assertEquals("tick()V", incoming.targetIdentifier());
    }
}
