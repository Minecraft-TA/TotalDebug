package com.github.minecraft_ta.totaldebug.network;

import com.github.minecraft_ta.totaldebug.protocol.relay.RelayedMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Puts relayed messages back together from their chunks, which arrive in order on one connection. The transfers in
 * progress share one byte budget: a chunk out of place, or one beyond the budget, drops its transfer. One assembler
 * serves one connection.
 */
public final class RelayAssembler {
    /** What the server holds for a client: its messages are requests, the largest a script's bytecode. */
    public static RelayAssembler toServer() {
        return new RelayAssembler(2 * 1024 * 1024, 4);
    }

    /** What the client holds for the server, which sends one message at a time. */
    public static RelayAssembler toClient() {
        return new RelayAssembler(RelayedMessage.MAX_BODY_BYTES, 8);
    }

    /** A message put back together. */
    public record Assembled(short messageId, byte[] body) {
    }

    private static final class Transfer {
        final short messageId;
        final int count;
        final List<byte[]> chunks = new ArrayList<>();
        int bytes;

        Transfer(short messageId, int count) {
            this.messageId = messageId;
            this.count = count;
        }
    }

    private final Map<UUID, Transfer> transfers = new LinkedHashMap<>();
    /** Bytes held across every transfer in progress. */
    private final int maxBytes;
    /** Transfers in progress at once; the oldest is dropped beyond this. */
    private final int maxTransfers;
    private int heldBytes;

    private RelayAssembler(int maxBytes, int maxTransfers) {
        this.maxBytes = maxBytes;
        this.maxTransfers = maxTransfers;
    }

    /** Takes one chunk; returns the message once its last chunk arrived. */
    public synchronized Optional<Assembled> accept(RelayChunk chunk) {
        Transfer transfer = this.transfers.get(chunk.transfer());
        if (transfer == null) {
            if (chunk.index() != 0) return Optional.empty();
            if (this.transfers.size() >= this.maxTransfers) drop(this.transfers.keySet().iterator().next());
            transfer = new Transfer(chunk.messageId(), chunk.count());
            this.transfers.put(chunk.transfer(), transfer);
        }
        if (chunk.index() != transfer.chunks.size() || chunk.count() != transfer.count || chunk.messageId() != transfer.messageId
                || this.heldBytes + chunk.bytes().length > this.maxBytes) {
            drop(chunk.transfer());
            return Optional.empty();
        }
        transfer.chunks.add(chunk.bytes());
        transfer.bytes += chunk.bytes().length;
        this.heldBytes += chunk.bytes().length;
        if (transfer.chunks.size() < transfer.count) return Optional.empty();
        drop(chunk.transfer());
        byte[] body = new byte[transfer.bytes];
        int offset = 0;
        for (byte[] bytes : transfer.chunks) {
            System.arraycopy(bytes, 0, body, offset, bytes.length);
            offset += bytes.length;
        }
        return Optional.of(new Assembled(transfer.messageId, body));
    }

    /** Forgets every transfer in progress, such as when the connection ended. */
    public synchronized void clear() {
        this.transfers.clear();
        this.heldBytes = 0;
    }

    private void drop(UUID id) {
        Transfer dropped = this.transfers.remove(id);
        if (dropped != null) this.heldBytes -= dropped.bytes;
    }
}
