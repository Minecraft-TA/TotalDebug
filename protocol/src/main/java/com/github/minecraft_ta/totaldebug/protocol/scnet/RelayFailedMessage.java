package com.github.minecraft_ta.totaldebug.protocol.scnet;

import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

import java.util.Objects;

/**
 * The game client could not carry a message to the server, such as one for a world the player has left or to a server
 * without TotalDebug: {@code messageId} is the message's protocol id and {@code correlation} its correlation, which
 * together name the request, and {@code reason} says why.
 */
public final class RelayFailedMessage extends AbstractMessage {
    private int correlation;
    private short messageId;
    private String reason;

    public RelayFailedMessage() {
    }

    public RelayFailedMessage(int correlation, short messageId, String reason) {
        this.correlation = correlation;
        this.messageId = messageId;
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    @Override
    public void read(ByteBufferInputStream input) {
        this.correlation = input.readInt();
        this.messageId = input.readShort();
        this.reason = input.readString();
    }

    @Override
    public void write(ByteBufferOutputStream output) {
        output.writeInt(this.correlation);
        output.writeShort(this.messageId);
        output.writeString(this.reason);
    }

    public int correlation() {
        return this.correlation;
    }

    public short messageId() {
        return this.messageId;
    }

    public String reason() {
        return this.reason;
    }
}
