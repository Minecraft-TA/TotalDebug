package com.github.minecraft_ta.totaldebug.client.companion.message;

import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionProtocolCodecTest {
    private static final HexFormat HEX = HexFormat.of();

    @Test
    void clientHelloMatchesTheSharedGoldenBytes() {
        ClientHelloMessage message = new ClientHelloMessage(4, "abc", 7, "p", "d", "i", "w", "m", "s");
        ByteBufferOutputStream output = new ByteBufferOutputStream();

        message.write(output);

        assertArrayEquals(
                HEX.parseHex("000000040000000361626300000000000000070000000170000000016400000001690000000177000000016d0000000173"),
                writtenBytes(output)
        );
    }

    @Test
    void serverHelloReadsTheSharedGoldenBytes() {
        byte[] golden = HEX.parseHex("0000000401000000000000000700000000");
        ServerHelloMessage message = new ServerHelloMessage();

        message.read(new ByteBufferInputStream(ByteBuffer.wrap(golden)));

        assertEquals(4, message.protocolVersion());
        assertTrue(message.accepted());
        assertEquals(7, message.capabilities());
        assertEquals("", message.rejectionReason());
    }

    private static byte[] writtenBytes(ByteBufferOutputStream output) {
        ByteBuffer buffer = output.getBuffer().duplicate();
        buffer.flip();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }
}
