package com.github.minecraft_ta.totaldebug.protocol.scnet;

import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

import java.util.Objects;

/**
 * The game client could not carry a message to the server, such as one for a world the player has left or to a server
 * without TotalDebug: {@code correlation} is the message's, and {@code reason} why.
 */
public final class RelayFailedMessage extends AbstractMessage {
    private int correlation;
    private String reason;

    public RelayFailedMessage() {
    }

    public RelayFailedMessage(int correlation, String reason) {
        this.correlation = correlation;
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    @Override
    public void read(ByteBufferInputStream input) {
        this.correlation = input.readInt();
        this.reason = input.readString();
    }

    @Override
    public void write(ByteBufferOutputStream output) {
        output.writeInt(this.correlation);
        output.writeString(this.reason);
    }

    public int correlation() {
        return this.correlation;
    }

    public String reason() {
        return this.reason;
    }
}
