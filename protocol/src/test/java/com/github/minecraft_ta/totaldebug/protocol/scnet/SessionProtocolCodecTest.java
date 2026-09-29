package com.github.minecraft_ta.totaldebug.protocol.scnet;

import com.github.minecraft_ta.totaldebug.protocol.CompanionProtocol;
import com.github.minecraft_ta.totaldebug.protocol.GoldenMessages;
import com.github.minecraft_ta.totaldebug.protocol.message.PreparedFilePayload;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;
import java.nio.ByteBuffer;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionProtocolCodecTest {
    private static final HexFormat HEX = HexFormat.of();

    @Test
    void clientHelloMatchesTheSharedGoldenBytes() {
        ClientHelloMessage message = new ClientHelloMessage(CompanionProtocol.VERSION, "abc", "p", "d", "w");
        ByteBufferOutputStream output = new ByteBufferOutputStream();

        message.write(output);

        assertArrayEquals(
                HEX.parseHex(GoldenMessages.CLIENT_HELLO),
                writtenBytes(output)
        );
    }

    @Test
    void serverHelloReadsTheSharedGoldenBytes() {
        byte[] golden = HEX.parseHex(GoldenMessages.SERVER_HELLO);
        ServerHelloMessage message = new ServerHelloMessage();

        message.read(new ByteBufferInputStream(ByteBuffer.wrap(golden)));

        assertEquals(CompanionProtocol.VERSION, message.protocolVersion());
        assertTrue(message.accepted());
        assertEquals("", message.rejectionReason());
    }

    @Test
    void debugTargetMatchesTheSharedGoldenBytes() {
        DebugTargetMessage message = new DebugTargetMessage("id", "game", DebugTargetMessage.LOCAL_JVM, 42);
        ByteBufferOutputStream output = new ByteBufferOutputStream();

        message.write(output);

        assertArrayEquals(
                HEX.parseHex(GoldenMessages.DEBUG_TARGET),
                writtenBytes(output)
        );
    }


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
    void aPreparedFileMatchesTheSharedGoldenBytes() {
        ByteBufferOutputStream output = new ByteBufferOutputStream();

        new PreparedFileMessage(PreparedFilePayload.ready(PreparedFilePayload.Kind.PACK_CATALOG, "id", "file")).write(output);

        assertArrayEquals(HEX.parseHex(GoldenMessages.PREPARED_FILE), writtenBytes(output));
        PreparedFileMessage message = new PreparedFileMessage();
        message.read(new ByteBufferInputStream(ByteBuffer.wrap(HEX.parseHex(GoldenMessages.PREPARED_FILE))));
        assertEquals(PreparedFilePayload.ready(PreparedFilePayload.Kind.PACK_CATALOG, "id", "file"), message.payload());
    }

    @Test
    void aReadyFileNamesItsFileAndItsInventoryUnlessItIsTheIcons() {
        assertThrows(IllegalArgumentException.class,
                () -> PreparedFilePayload.ready(PreparedFilePayload.Kind.RUNTIME_INVENTORY, "id", ""));
        assertThrows(IllegalArgumentException.class,
                () -> PreparedFilePayload.ready(PreparedFilePayload.Kind.PACK_CATALOG, "", "file"));
        assertEquals("", PreparedFilePayload.ready(PreparedFilePayload.Kind.ITEM_ICONS, "", "icons.zip").inventoryId());
        assertEquals("", PreparedFilePayload.failed(PreparedFilePayload.Kind.RUNTIME_INVENTORY, "", "Capture failed").file());
    }

    @Test
    void aPreparedFileOfAnUnknownKindIsRefused() {
        ByteBufferOutputStream output = new ByteBufferOutputStream();
        output.writeInt(PreparedFilePayload.Kind.values().length);
        output.writeInt(0);

        assertThrows(IllegalArgumentException.class, () -> new PreparedFileMessage().read(
                new ByteBufferInputStream(ByteBuffer.wrap(writtenBytes(output)))));
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
