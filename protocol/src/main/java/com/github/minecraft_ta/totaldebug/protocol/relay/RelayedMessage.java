package com.github.minecraft_ta.totaldebug.protocol.relay;

import com.github.tth05.scnet.message.impl.DefaultMessageProcessor;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

import java.util.Arrays;
import java.util.Objects;

/**
 * A Companion protocol message carried to or from the game's server, unread by the game client: its protocol id and
 * encoded body. {@code correlation} is Companion's id for the request, such as a script run, or 0; {@code gameSession}
 * is the joined world the message is only valid in, or empty. Both are empty on the way back. See
 * {@code docs/MOD_SIDES.md}.
 */
public record RelayedMessage(int correlation, String gameSession, short messageId, byte[] body) {
    public static final int MAX_SESSION_LENGTH = 64;
    /** Correlation, game session as UTF-8 with its length, message id and body length. */
    public static final int ENVELOPE_BYTES = Integer.BYTES + Integer.BYTES + 3 * MAX_SESSION_LENGTH + Short.BYTES + Integer.BYTES;
    /**
     * A relayed message and its envelope fit one frame of the Companion connection. Every message the server sends is
     * budgeted for it; the largest is an execution result.
     */
    public static final int MAX_BODY_BYTES = DefaultMessageProcessor.DEFAULT_MAX_FRAME_SIZE - ENVELOPE_BYTES;

    public RelayedMessage {
        Objects.requireNonNull(gameSession, "gameSession");
        Objects.requireNonNull(body, "body");
        if (gameSession.length() > MAX_SESSION_LENGTH) throw new IllegalArgumentException("Game session too long");
        if (body.length > MAX_BODY_BYTES) throw new IllegalArgumentException("Relayed message too large: " + body.length);
        body = body.clone();
    }

    @Override
    public byte[] body() {
        return this.body.clone();
    }

    public static RelayedMessage read(ByteBufferInputStream input) {
        int correlation = input.readInt();
        String gameSession = input.readString();
        short messageId = input.readShort();
        int length = input.readInt();
        if (length < 0 || length > MAX_BODY_BYTES) throw new IllegalArgumentException("Invalid relayed message length: " + length);
        return new RelayedMessage(correlation, gameSession, messageId, input.readByteArray(length));
    }

    public void write(ByteBufferOutputStream output) {
        output.writeInt(this.correlation);
        output.writeString(this.gameSession);
        output.writeShort(this.messageId);
        output.writeInt(this.body.length);
        output.writeByteArray(this.body);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RelayedMessage that && this.correlation == that.correlation && this.messageId == that.messageId
                && this.gameSession.equals(that.gameSession) && Arrays.equals(this.body, that.body);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.correlation, this.gameSession, this.messageId, Arrays.hashCode(this.body));
    }
}
