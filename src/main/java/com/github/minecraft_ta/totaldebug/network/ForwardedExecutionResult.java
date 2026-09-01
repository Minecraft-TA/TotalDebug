package com.github.minecraft_ta.totaldebug.network;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.script.ExecutionResult;
import com.github.minecraft_ta.totaldebug.script.ExecutionResultCodec;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Chunks one canonical execution result across Minecraft's clientbound payload limit. */
public record ForwardedExecutionResult(int scriptId, ExecutionResult result) {
    public static final ResourceLocation MESSAGE_ID = ResourceLocation.fromNamespaceAndPath(
            TotalDebug.MOD_ID,
            "execution_result_v1"
    );
    private static final int CHUNK_METADATA_BYTES = 2 * Long.BYTES
            + Integer.BYTES
            + 1 // chunk index VarInt
            + 1 // chunk count VarInt
            + 4 // total byte count VarInt
            + 3; // chunk length VarInt
    static final int MAX_CHUNK_BYTES = ForwardedCompanionPayload.MAX_BODY_BYTES - CHUNK_METADATA_BYTES;
    static final int MAX_CHUNKS = (ExecutionResultCodec.MAX_WIRE_BYTES + MAX_CHUNK_BYTES - 1)
            / MAX_CHUNK_BYTES;

    public ForwardedExecutionResult {
        result = Objects.requireNonNull(result, "result");
    }

    public List<ForwardedCompanionPayload> toPayloads() {
        byte[] resultBytes = ExecutionResultCodec.encode(this.result).json().getBytes(StandardCharsets.UTF_8);
        int chunkCount = Math.max(1, (resultBytes.length + MAX_CHUNK_BYTES - 1) / MAX_CHUNK_BYTES);
        UUID transferId = UUID.randomUUID();
        List<ForwardedCompanionPayload> payloads = new ArrayList<>(chunkCount);
        for (int chunkIndex = 0; chunkIndex < chunkCount; chunkIndex++) {
            int start = chunkIndex * MAX_CHUNK_BYTES;
            int end = Math.min(resultBytes.length, start + MAX_CHUNK_BYTES);
            byte[] content = Arrays.copyOfRange(resultBytes, start, end);
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                buffer.writeUUID(transferId);
                buffer.writeInt(this.scriptId);
                buffer.writeVarInt(chunkIndex);
                buffer.writeVarInt(chunkCount);
                buffer.writeVarInt(resultBytes.length);
                buffer.writeByteArray(content);
                byte[] body = new byte[buffer.readableBytes()];
                buffer.readBytes(body);
                payloads.add(new ForwardedCompanionPayload(MESSAGE_ID, body));
            } finally {
                buffer.release();
            }
        }
        return List.copyOf(payloads);
    }

    public static Chunk decodeChunk(ForwardedCompanionPayload payload) {
        Objects.requireNonNull(payload, "payload");
        if (!MESSAGE_ID.equals(payload.messageId())) {
            throw new IllegalArgumentException("Unsupported forwarded companion message: " + payload.messageId());
        }
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(payload.body()));
        try {
            UUID transferId = buffer.readUUID();
            int scriptId = buffer.readInt();
            int chunkIndex = buffer.readVarInt();
            int chunkCount = buffer.readVarInt();
            int totalBytes = buffer.readVarInt();
            byte[] content = buffer.readByteArray(MAX_CHUNK_BYTES);
            if (buffer.isReadable()) {
                throw new IllegalArgumentException("Forwarded execution-result chunk contains trailing bytes");
            }
            return new Chunk(transferId, scriptId, chunkIndex, chunkCount, totalBytes, content);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Malformed forwarded execution-result chunk", exception);
        } finally {
            buffer.release();
        }
    }

    public record Chunk(
            UUID transferId,
            int scriptId,
            int chunkIndex,
            int chunkCount,
            int totalBytes,
            byte[] content
    ) {
        public Chunk {
            transferId = Objects.requireNonNull(transferId, "transferId");
            content = Arrays.copyOf(Objects.requireNonNull(content, "content"), content.length);
            if (chunkCount < 1 || chunkCount > MAX_CHUNKS
                    || chunkIndex < 0 || chunkIndex >= chunkCount) {
                throw new IllegalArgumentException("Invalid execution-result chunk index");
            }
            if (totalBytes < 1 || totalBytes > ExecutionResultCodec.MAX_WIRE_BYTES) {
                throw new IllegalArgumentException("Invalid execution-result byte count: " + totalBytes);
            }
            if (content.length < 1 || content.length > MAX_CHUNK_BYTES
                    || chunkCount > totalBytes
                    || (long) chunkCount * MAX_CHUNK_BYTES < totalBytes) {
                throw new IllegalArgumentException("Invalid execution-result chunk size: " + content.length);
            }
        }

        @Override
        public byte[] content() {
            return Arrays.copyOf(this.content, this.content.length);
        }
    }
}
