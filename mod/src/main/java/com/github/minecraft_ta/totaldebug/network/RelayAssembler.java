package com.github.minecraft_ta.totaldebug.network;

import com.github.minecraft_ta.totaldebug.protocol.relay.RelayedMessage;

import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Puts relayed messages back together from their chunks, which arrive in order on one connection. A chunk out of place,
 * or a message larger than the assembler takes, drops its transfer. One assembler serves one connection.
 */
public final class RelayAssembler {
    /** What the server takes from a client: its messages are requests, the largest a script's bytecode. */
    public static RelayAssembler toServer() {
        return new RelayAssembler(2 * 1024 * 1024, 4);
    }

    /** What the client takes from the server, whose class manifest is its largest message. */
    public static RelayAssembler toClient() {
        return new RelayAssembler(RelayedMessage.MAX_BODY_BYTES, 8);
    }

    /** A message being put back together. */
    public record Assembled(short messageId, byte[] body) {
    }

    private static final class Transfer {
        final short messageId;
        final int count;
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        int next;

        Transfer(short messageId, int count) {
            this.messageId = messageId;
            this.count = count;
        }
    }

    private final Map<UUID, Transfer> transfers = new LinkedHashMap<>();
    private final int maxBytes;
    /** Transfers in progress at once; the oldest is dropped beyond this. */
    private final int maxTransfers;

    private RelayAssembler(int maxBytes, int maxTransfers) {
        this.maxBytes = maxBytes;
        this.maxTransfers = maxTransfers;
    }

    /** Takes one chunk; returns the message once its last chunk arrived. */
    public synchronized Optional<Assembled> accept(RelayChunk chunk) {
        Transfer transfer = this.transfers.get(chunk.transfer());
        if (transfer == null) {
            if (chunk.index() != 0) return Optional.empty();
            if (this.transfers.size() >= this.maxTransfers) this.transfers.remove(this.transfers.keySet().iterator().next());
            transfer = new Transfer(chunk.messageId(), chunk.count());
            this.transfers.put(chunk.transfer(), transfer);
        }
        if (chunk.index() != transfer.next || chunk.count() != transfer.count || chunk.messageId() != transfer.messageId
                || transfer.body.size() + chunk.bytes().length > this.maxBytes) {
            this.transfers.remove(chunk.transfer());
            return Optional.empty();
        }
        transfer.body.writeBytes(chunk.bytes());
        transfer.next++;
        if (transfer.next < transfer.count) return Optional.empty();
        this.transfers.remove(chunk.transfer());
        return Optional.of(new Assembled(transfer.messageId, transfer.body.toByteArray()));
    }

    /** Forgets every transfer in progress, such as when the connection ended. */
    public synchronized void clear() {
        this.transfers.clear();
    }
}
