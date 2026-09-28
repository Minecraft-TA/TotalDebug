package com.github.minecraft_ta.totaldebug.protocol.scnet;

import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

import java.util.Objects;

/**
 * The server's answer to the {@link ServerScriptsRequestMessage} {@code request}: it runs this player's scripts, or
 * {@code refusal} says why not.
 */
public final class ServerScriptsMessage extends AbstractMessage {
    public static final int MAX_REFUSAL_LENGTH = 2048;
    private int request;
    private String refusal;

    public ServerScriptsMessage() {
    }

    public ServerScriptsMessage(int request, String refusal) {
        this.request = request;
        this.refusal = checked(refusal);
    }

    public static ServerScriptsMessage allowed(int request) {
        return new ServerScriptsMessage(request, "");
    }

    public int request() {
        return this.request;
    }

    public boolean isAllowed() {
        return this.refusal.isEmpty();
    }

    /** Why the server does not run this player's scripts, or empty when it does. */
    public String refusal() {
        return this.refusal;
    }

    @Override
    public void read(ByteBufferInputStream input) {
        this.request = input.readInt();
        this.refusal = checked(input.readString());
    }

    @Override
    public void write(ByteBufferOutputStream output) {
        output.writeInt(this.request);
        output.writeString(this.refusal);
    }

    private static String checked(String refusal) {
        Objects.requireNonNull(refusal, "refusal");
        if (refusal.length() > MAX_REFUSAL_LENGTH) throw new IllegalArgumentException("Server script refusal too long");
        return refusal;
    }
}
