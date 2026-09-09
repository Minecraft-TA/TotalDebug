package com.github.minecraft_ta.totaldebug.protocol.scnet;

import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class ServerManifestMessageTest {
    @Test
    void largeManifestRoundTripsAcrossBoundedFrames() {
        byte[] bytes = new byte[2 * ServerManifestMessage.CHUNK_BYTES + 7];
        new Random(7).nextBytes(bytes);
        var assembler = new ServerManifestMessage.Assembler();
        byte[] result = null;
        for (var message : ServerManifestMessage.split("session", "request", 3, bytes)) {
            var output = new ByteBufferOutputStream();
            message.write(output);
            var input = output.getBuffer().duplicate();
            input.flip();
            var read = new ServerManifestMessage();
            read.read(new ByteBufferInputStream(input));
            assertFalse(input.hasRemaining());
            assertEquals("request", read.requestId());
            assertEquals(3, read.source());
            assertFalse(read.baseline());
            result = assembler.accept(read);
        }
        assertArrayEquals(bytes, result);
    }

    @Test
    void sourceRequestsRoundTripAndRejectInvalidIdentityOrSource() {
        var output = new ByteBufferOutputStream();
        new ServerSourceRequestMessage("session", "request", 27).write(output);
        var input = output.getBuffer().duplicate();
        input.flip();
        var read = new ServerSourceRequestMessage();
        read.read(new ByteBufferInputStream(input));
        assertEquals("session", read.sessionId());
        assertEquals("request", read.requestId());
        assertEquals(27, read.source());
        assertFalse(input.hasRemaining());
        assertThrows(IllegalArgumentException.class, () -> new ServerSourceRequestMessage("", "request", 0));
        assertThrows(IllegalArgumentException.class, () -> new ServerSourceRequestMessage("session", "", 0));
        assertThrows(IllegalArgumentException.class, () -> new ServerSourceRequestMessage("session", "request", 4096));
    }

    @Test
    void sourceChunksCannotMixRequestsOrSources() {
        var first = ServerManifestMessage.split("session", "first", 1, new byte[ServerManifestMessage.CHUNK_BYTES + 1]);
        var second = ServerManifestMessage.split("session", "second", 1, new byte[ServerManifestMessage.CHUNK_BYTES + 1]);
        var assembler = new ServerManifestMessage.Assembler();
        assembler.accept(first.getFirst());
        assertThrows(IllegalArgumentException.class, () -> assembler.accept(second.getLast()));
        var other = ServerManifestMessage.split("session", "first", 2, new byte[ServerManifestMessage.CHUNK_BYTES + 1]);
        assembler.accept(first.getFirst());
        assertThrows(IllegalArgumentException.class, () -> assembler.accept(other.getLast()));
    }

    @Test
    void disconnectAndNewSessionDiscardPartialTransfer() {
        var assembler = new ServerManifestMessage.Assembler();
        var messages = ServerManifestMessage.split("old", new byte[ServerManifestMessage.CHUNK_BYTES + 1]);
        assertNull(assembler.accept(messages.getFirst()));
        assertNull(assembler.accept(ServerManifestMessage.unavailable("Disconnected")));
        assertThrows(IllegalArgumentException.class, () -> assembler.accept(messages.getLast()));
        assertArrayEquals(new byte[]{3}, assembler.accept(ServerManifestMessage.split("new", new byte[]{3}).getFirst()));
    }

    @Test
    void rejectsUnboundedAndOutOfOrderTransfers() {
        assertThrows(IllegalArgumentException.class,
                () -> new ServerManifestMessage("session", "", 0, ServerManifestMessage.MAX_BYTES + 1, new byte[]{1}));
        var assembler = new ServerManifestMessage.Assembler();
        var chunks = ServerManifestMessage.split("session", new byte[ServerManifestMessage.CHUNK_BYTES + 1]);
        assertThrows(IllegalArgumentException.class, () -> assembler.accept(chunks.getLast()));
        assembler.accept(chunks.getFirst());
        assertThrows(IllegalArgumentException.class, () -> assembler.accept(new ServerManifestMessage("other", "",
                chunks.getLast().offset(), chunks.getLast().total(), chunks.getLast().bytes())));
    }
}
