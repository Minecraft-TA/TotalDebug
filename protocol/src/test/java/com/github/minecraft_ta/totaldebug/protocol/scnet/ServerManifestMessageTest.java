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
        for (var message : ServerManifestMessage.split("session", bytes)) {
            var output = new ByteBufferOutputStream();
            message.write(output);
            var input = output.getBuffer().duplicate();
            input.flip();
            var read = new ServerManifestMessage();
            read.read(new ByteBufferInputStream(input));
            assertFalse(input.hasRemaining());
            result = assembler.accept(read);
        }
        assertArrayEquals(bytes, result);
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
