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
        ClientHelloMessage message = new ClientHelloMessage(7, "abc", 7, "p", "d", "w");
        ByteBufferOutputStream output = new ByteBufferOutputStream();

        message.write(output);

        assertArrayEquals(
                HEX.parseHex("00000007000000036162630000000000000007000000017000000001640000000177"),
                writtenBytes(output)
        );
    }

    @Test
    void serverHelloReadsTheSharedGoldenBytes() {
        byte[] golden = HEX.parseHex("0000000701000000000000000700000000");
        ServerHelloMessage message = new ServerHelloMessage();

        message.read(new ByteBufferInputStream(ByteBuffer.wrap(golden)));

        assertEquals(7, message.protocolVersion());
        assertTrue(message.accepted());
        assertEquals(7, message.capabilities());
        assertEquals("", message.rejectionReason());
    }

    @Test
    void runtimeInventoryMatchesTheSharedGoldenBytes() {
        RuntimeInventoryMessage message = RuntimeInventoryMessage.available("id", "file");
        ByteBufferOutputStream output = new ByteBufferOutputStream();

        message.write(output);

        assertArrayEquals(
                HEX.parseHex("000000010000000269640000000466696c6500000000"),
                writtenBytes(output)
        );
    }

    @Test
    void debugTargetMatchesTheSharedGoldenBytes() {
        DebugTargetMessage message = new DebugTargetMessage("id", "game", DebugTargetMessage.LOCAL_JVM, 42);
        ByteBufferOutputStream output = new ByteBufferOutputStream();

        message.write(output);

        assertArrayEquals(
                HEX.parseHex("0000000269640000000467616d6501000000000000002a"),
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
