package com.github.minecraft_ta.totaldebug.network;

import com.github.minecraft_ta.totaldebug.protocol.relay.RelayedMessage;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelayChunkTest {
    @Test
    void aMessageLargerThanOnePayloadIsSplitAndPutBackTogether() {
        byte[] body = new byte[RelayChunk.TO_SERVER_BYTES * 2 + 17];
        IntStream.range(0, body.length).forEach(index -> body[index] = (byte) index);
        List<RelayChunk> chunks = RelayChunk.split((short) 8, body, RelayChunk.TO_SERVER_BYTES);
        assertEquals(3, chunks.size(), "a script's bytecode is no longer capped at one payload");

        RelayAssembler assembler = RelayAssembler.toClient();
        assertTrue(assembler.accept(chunks.get(0)).isEmpty());
        assertTrue(assembler.accept(chunks.get(1)).isEmpty());
        RelayAssembler.Assembled assembled = assembler.accept(chunks.get(2)).orElseThrow();

        assertEquals(8, assembled.messageId());
        assertArrayEquals(body, assembled.body());
    }

    @Test
    void anEmptyMessageTravelsAsOneChunk() {
        List<RelayChunk> chunks = RelayChunk.split((short) 45, new byte[0], RelayChunk.TO_SERVER_BYTES);

        assertEquals(1, chunks.size());
        assertEquals(0, RelayAssembler.toClient().accept(chunks.getFirst()).orElseThrow().body().length);
    }

    @Test
    void aChunkOutOfPlaceDropsItsTransfer() {
        List<RelayChunk> chunks = RelayChunk.split((short) 8, new byte[RelayChunk.TO_SERVER_BYTES * 3], RelayChunk.TO_SERVER_BYTES);
        RelayAssembler assembler = RelayAssembler.toClient();

        assembler.accept(chunks.get(0));
        assertTrue(assembler.accept(chunks.get(2)).isEmpty(), "the missing middle drops the transfer");
        assertEquals(Optional.empty(), assembler.accept(chunks.get(1)), "and nothing is put together from what is left");
    }

    @Test
    void aTransferThatDoesNotStartAtItsFirstChunkIsIgnored() {
        RelayChunk late = new RelayChunk(UUID.randomUUID(), 1, 2, (short) 8, new byte[1]);

        assertTrue(RelayAssembler.toClient().accept(late).isEmpty());
    }

    @Test
    void theServerTakesOnlyRequestSizedMessagesFromAClient() {
        List<RelayChunk> chunks = RelayChunk.split((short) 8, new byte[3 * 1024 * 1024], RelayChunk.TO_SERVER_BYTES);
        RelayAssembler assembler = RelayAssembler.toServer();

        assertTrue(chunks.stream().map(assembler::accept).allMatch(Optional::isEmpty), "a client cannot make the server hold megabytes");
    }

    @Test
    void aChunkSurvivesTheWire() {
        RelayChunk chunk = new RelayChunk(UUID.randomUUID(), 1, 3, (short) 9, new byte[]{4, 5, 6});
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

        RelayChunk.STREAM_CODEC.encode(buffer, chunk);
        RelayChunk read = RelayChunk.STREAM_CODEC.decode(buffer);

        assertEquals(chunk.transfer(), read.transfer());
        assertEquals(1, read.index());
        assertEquals(3, read.count());
        assertEquals(9, read.messageId());
        assertArrayEquals(new byte[]{4, 5, 6}, read.bytes());
    }

    @Test
    void aChunkBeyondItsCountIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new RelayChunk(UUID.randomUUID(), 3, 3, (short) 8, new byte[0]));
        assertThrows(IllegalArgumentException.class,
                () -> new RelayChunk(UUID.randomUUID(), 0, RelayedMessage.MAX_BODY_BYTES, (short) 8, new byte[0]));
    }
}
