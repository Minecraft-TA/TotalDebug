package com.github.minecraft_ta.totalDebugCompanion.session;

import com.github.minecraft_ta.totalDebugCompanion.messages.session.ClientHelloMessage;
import com.github.minecraft_ta.totalDebugCompanion.messages.session.ServerHelloMessage;
import com.github.minecraft_ta.totalDebugCompanion.messages.session.RuntimeInventoryMessage;
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
        byte[] golden = HEX.parseHex("00000005000000036162630000000000000007000000017000000001640000000177");
        ClientHelloMessage message = new ClientHelloMessage();

        message.read(new ByteBufferInputStream(ByteBuffer.wrap(golden)));

        assertEquals(5, message.protocolVersion());
        assertEquals("abc", message.token());
        assertEquals(7, message.requestedCapabilities());
        assertEquals("p", message.profileId());
        assertEquals("d", message.dataDirectory());
        assertEquals("w", message.workspaceDirectory());
    }

    @Test
    void serverHelloMatchesTheSharedGoldenBytes() {
        ServerHelloMessage message = ServerHelloMessage.accepted(7);
        ByteBufferOutputStream output = new ByteBufferOutputStream();

        message.write(output);

        assertArrayEquals(
                HEX.parseHex("0000000501000000000000000700000000"),
                writtenBytes(output)
        );
    }

    @Test
    void runtimeInventoryReadsTheSharedGoldenBytes() {
        RuntimeInventoryMessage message = new RuntimeInventoryMessage();

        message.read(new ByteBufferInputStream(ByteBuffer.wrap(
                HEX.parseHex("000000010000000269640000000466696c6500000000")
        )));

        assertEquals(RuntimeInventoryMessage.AVAILABLE, message.state());
        assertEquals("id", message.inventoryId());
        assertEquals("file", message.inventoryFile());
        assertEquals("", message.detail());
    }

    private static byte[] writtenBytes(ByteBufferOutputStream output) {
        ByteBuffer buffer = output.getBuffer().duplicate();
        buffer.flip();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }
}
