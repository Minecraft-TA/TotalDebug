package com.github.minecraft_ta.totaldebug.client.companion.message;

import com.github.tth05.scnet.message.AbstractMessageIncoming;
import com.github.tth05.scnet.util.ByteBufferInputStream;

public final class ServerHelloMessage extends AbstractMessageIncoming {
    private int protocolVersion;
    private boolean accepted;
    private String rejectionReason;

    @Override
    public void read(ByteBufferInputStream messageStream) {
        this.protocolVersion = messageStream.readInt();
        this.accepted = messageStream.readBoolean();
        this.rejectionReason = messageStream.readString();
    }

    public int protocolVersion() {
        return this.protocolVersion;
    }

    public boolean accepted() {
        return this.accepted;
    }

    public String rejectionReason() {
        return this.rejectionReason;
    }
}
