package com.github.minecraft_ta.totaldebug.client.companion.message;

import com.github.tth05.scnet.message.AbstractMessageOutgoing;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

import java.util.Objects;

public final class ClientHelloMessage extends AbstractMessageOutgoing {
    private final int protocolVersion;
    private final String token;
    private final long requestedCapabilities;

    public ClientHelloMessage(int protocolVersion, String token, long requestedCapabilities) {
        this.protocolVersion = protocolVersion;
        this.token = Objects.requireNonNull(token, "token");
        this.requestedCapabilities = requestedCapabilities;
    }

    @Override
    public void write(ByteBufferOutputStream messageStream) {
        messageStream.writeInt(this.protocolVersion);
        messageStream.writeString(this.token);
        messageStream.writeLong(this.requestedCapabilities);
    }

    public int protocolVersion() {
        return this.protocolVersion;
    }

    public String token() {
        return this.token;
    }

    public long requestedCapabilities() {
        return this.requestedCapabilities;
    }
}
