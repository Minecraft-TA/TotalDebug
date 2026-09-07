package com.github.minecraft_ta.totaldebug.protocol.scnet.companion;

import com.github.minecraft_ta.totaldebug.protocol.CompanionProtocol;
import com.github.minecraft_ta.totaldebug.protocol.GoldenMessages;
import com.github.minecraft_ta.totaldebug.protocol.scnet.companion.ClientHelloMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.companion.ServerHelloMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.companion.RuntimeInventoryMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.companion.DebugTargetMessage;
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
        byte[] golden = HEX.parseHex(GoldenMessages.CLIENT_HELLO);
        ClientHelloMessage message = new ClientHelloMessage();

        message.read(new ByteBufferInputStream(ByteBuffer.wrap(golden)));

        assertEquals(CompanionProtocol.VERSION, message.protocolVersion());
        assertEquals("abc", message.token());
        assertEquals("p", message.profileId());
        assertEquals("d", message.dataDirectory());
        assertEquals("w", message.workspaceDirectory());
    }

    @Test
    void serverHelloMatchesTheSharedGoldenBytes() {
        ServerHelloMessage message = ServerHelloMessage.accept();
        ByteBufferOutputStream output = new ByteBufferOutputStream();

        message.write(output);

        assertArrayEquals(
                HEX.parseHex(GoldenMessages.SERVER_HELLO),
                writtenBytes(output)
        );
    }

    @Test
    void runtimeInventoryReadsTheSharedGoldenBytes() {
        RuntimeInventoryMessage message = new RuntimeInventoryMessage();

        message.read(new ByteBufferInputStream(ByteBuffer.wrap(
                HEX.parseHex(GoldenMessages.RUNTIME_INVENTORY)
        )));

        assertEquals(RuntimeInventoryMessage.AVAILABLE, message.state());
        assertEquals("id", message.inventoryId());
        assertEquals("file", message.inventoryFile());
        assertEquals("", message.detail());
    }

    @Test
    void debugTargetReadsTheSharedGoldenBytes() {
        DebugTargetMessage message = new DebugTargetMessage();

        message.read(new ByteBufferInputStream(ByteBuffer.wrap(
                HEX.parseHex(GoldenMessages.DEBUG_TARGET)
        )));

        assertEquals("id", message.targetId());
        assertEquals("game", message.displayName());
        assertEquals(DebugTargetMessage.LOCAL_JVM, message.targetKind());
        assertEquals(42, message.processId());
    }

    private static byte[] writtenBytes(ByteBufferOutputStream output) {
        ByteBuffer buffer = output.getBuffer().duplicate();
        buffer.flip();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }
}
