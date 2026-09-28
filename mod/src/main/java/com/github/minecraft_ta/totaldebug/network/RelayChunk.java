package com.github.minecraft_ta.totaldebug.network;

import com.github.minecraft_ta.totaldebug.protocol.relay.RelayedMessage;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One piece of a Companion protocol message on its way between the game client and the server: the transfer it belongs
 * to, the Companion connection it came from or answers, its place among the transfer's {@code count} pieces, the
 * message's protocol id and a slice of its body. See {@code docs/MOD_SIDES.md}.
 */
public record RelayChunk(UUID transfer, int companion, int index, int count, short messageId, byte[] bytes) {
    /** Below Minecraft's 32 KiB limit for a payload sent to the server, with room for the rest of the chunk. */
    public static final int TO_SERVER_BYTES = 30_000;
    /** Below Minecraft's 1 MiB limit for a payload sent to the client. */
    public static final int TO_CLIENT_BYTES = 1_000_000;
    public static final int MAX_COUNT = RelayedMessage.MAX_BODY_BYTES / TO_SERVER_BYTES + 1;

    public static final StreamCodec<FriendlyByteBuf, RelayChunk> STREAM_CODEC = StreamCodec.of(
            (buffer, chunk) -> {
                buffer.writeUUID(chunk.transfer);
                buffer.writeVarInt(chunk.companion);
                buffer.writeVarInt(chunk.index);
                buffer.writeVarInt(chunk.count);
                buffer.writeShort(chunk.messageId);
                buffer.writeByteArray(chunk.bytes);
            },
            buffer -> new RelayChunk(buffer.readUUID(), buffer.readVarInt(), buffer.readVarInt(), buffer.readVarInt(), buffer.readShort(),
                    buffer.readByteArray(TO_CLIENT_BYTES)));

    public RelayChunk {
        Objects.requireNonNull(transfer, "transfer");
        Objects.requireNonNull(bytes, "bytes");
        if (companion < 1) throw new IllegalArgumentException("Invalid Companion connection " + companion);
        if (count < 1 || count > MAX_COUNT || index < 0 || index >= count) {
            throw new IllegalArgumentException("Invalid relay chunk " + index + " of " + count);
        }
        if (bytes.length > TO_CLIENT_BYTES) throw new IllegalArgumentException("Relay chunk too large: " + bytes.length);
    }

    /** {@code body} of message {@code messageId} in pieces of at most {@code size} bytes, one transfer of {@code companion}'s. */
    public static List<RelayChunk> split(int companion, short messageId, byte[] body, int size) {
        UUID transfer = UUID.randomUUID();
        int count = Math.max(1, (body.length + size - 1) / size);
        List<RelayChunk> chunks = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            int start = index * size;
            chunks.add(new RelayChunk(transfer, companion, index, count, messageId,
                    Arrays.copyOfRange(body, start, Math.min(body.length, start + size))));
        }
        return chunks;
    }
}
