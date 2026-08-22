package com.github.minecraft_ta.totalDebugCompanion.session;

import com.github.minecraft_ta.totalDebugCompanion.messages.session.ClientHelloMessage;
import com.github.minecraft_ta.totalDebugCompanion.messages.session.ServerHelloMessage;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SessionProtocolCodecTest {
    private static final HexFormat HEX = HexFormat.of();

    @Test
    void clientHelloReadsTheSharedGoldenBytes() {
        byte[] golden = HEX.parseHex("00000002000000036162630000000000000007");
        ClientHelloMessage message = new ClientHelloMessage();

        message.read(new ByteBufferInputStream(ByteBuffer.wrap(golden)));

        assertEquals(2, message.protocolVersion());
        assertEquals("abc", message.token());
        assertEquals(7, message.requestedCapabilities());
    }

    @Test
    void serverHelloMatchesTheSharedGoldenBytes() {
        ServerHelloMessage message = ServerHelloMessage.accepted(7);
        ByteBufferOutputStream output = new ByteBufferOutputStream();

        message.write(output);

        assertArrayEquals(
                HEX.parseHex("0000000201000000000000000700000000"),
                writtenBytes(output)
        );
    }

    private static byte[] writtenBytes(ByteBufferOutputStream output) {
        ByteBuffer buffer = output.getBuffer().duplicate();
        buffer.flip();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }
}
