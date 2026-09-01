package com.github.minecraft_ta.totaldebug.network;

import com.github.minecraft_ta.totaldebug.script.ExecutionResult;
import com.github.minecraft_ta.totaldebug.script.ExecutionResultCodec;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Reassembles bounded execution-result chunks received from a TotalDebug server. */
public final class ForwardedExecutionResultAssembler {
    static final int MAX_PENDING_TRANSFERS = 16;
    static final long MAX_PENDING_BYTES = (long) ExecutionResultCodec.MAX_WIRE_BYTES * 2;

    private final Map<UUID, Transfer> transfers = new LinkedHashMap<>();
    private long pendingBytes;

    public synchronized Optional<ForwardedExecutionResult> accept(ForwardedExecutionResult.Chunk chunk) {
        Transfer transfer = this.transfers.get(chunk.transferId());
        if (transfer == null) {
            while (!this.transfers.isEmpty()
                    && (this.transfers.size() == MAX_PENDING_TRANSFERS
                    || this.pendingBytes + chunk.totalBytes() > MAX_PENDING_BYTES)) {
                remove(this.transfers.keySet().iterator().next());
            }
            transfer = new Transfer(chunk);
            this.transfers.put(chunk.transferId(), transfer);
            this.pendingBytes += chunk.totalBytes();
        }
        Optional<byte[]> completed;
        try {
            completed = transfer.accept(chunk);
        } catch (RuntimeException exception) {
            remove(chunk.transferId());
            throw exception;
        }
        if (completed.isEmpty()) {
            return Optional.empty();
        }
        remove(chunk.transferId());
        String json = decodeUtf8(completed.orElseThrow());
        ExecutionResult result = ExecutionResultCodec.decode(json);
        return Optional.of(new ForwardedExecutionResult(chunk.scriptId(), result));
    }

    public synchronized void clear() {
        this.transfers.clear();
        this.pendingBytes = 0;
    }

    private void remove(UUID transferId) {
        Transfer removed = this.transfers.remove(transferId);
        if (removed != null) {
            this.pendingBytes -= removed.totalBytes;
        }
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("Execution-result transfer is not valid UTF-8", exception);
        }
    }

    private static final class Transfer {
        private final int scriptId;
        private final int totalBytes;
        private final byte[][] chunks;
        private int receivedChunks;
        private int receivedBytes;

        private Transfer(ForwardedExecutionResult.Chunk first) {
            this.scriptId = first.scriptId();
            this.totalBytes = first.totalBytes();
            this.chunks = new byte[first.chunkCount()][];
        }

        private Optional<byte[]> accept(ForwardedExecutionResult.Chunk chunk) {
            if (chunk.scriptId() != this.scriptId
                    || chunk.totalBytes() != this.totalBytes
                    || chunk.chunkCount() != this.chunks.length) {
                throw new IllegalArgumentException("Execution-result transfer metadata changed between chunks");
            }
            byte[] content = chunk.content();
            byte[] existing = this.chunks[chunk.chunkIndex()];
            if (existing != null) {
                if (!Arrays.equals(existing, content)) {
                    throw new IllegalArgumentException("Execution-result chunk was replaced with different bytes");
                }
                return Optional.empty();
            }
            if ((long) this.receivedBytes + content.length > this.totalBytes) {
                throw new IllegalArgumentException("Execution-result chunks exceed the declared byte count");
            }
            this.chunks[chunk.chunkIndex()] = content;
            this.receivedChunks++;
            this.receivedBytes += content.length;
            if (this.receivedChunks != this.chunks.length) {
                return Optional.empty();
            }
            if (this.receivedBytes != this.totalBytes) {
                throw new IllegalArgumentException("Execution-result chunks do not match the declared byte count");
            }
            byte[] joined = new byte[this.totalBytes];
            int offset = 0;
            for (byte[] part : this.chunks) {
                System.arraycopy(part, 0, joined, offset, part.length);
                offset += part.length;
            }
            return Optional.of(joined);
        }
    }
}
