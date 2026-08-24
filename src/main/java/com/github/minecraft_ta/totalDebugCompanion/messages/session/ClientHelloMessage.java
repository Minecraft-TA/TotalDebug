package com.github.minecraft_ta.totalDebugCompanion.messages.session;

import com.github.tth05.scnet.message.AbstractMessageIncoming;
import com.github.tth05.scnet.util.ByteBufferInputStream;

public final class ClientHelloMessage extends AbstractMessageIncoming {
    private int protocolVersion;
    private String token;
    private long requestedCapabilities;
    private String profileId;
    private String dataDirectory;
    private String workspaceDirectory;

    @Override
    public void read(ByteBufferInputStream messageStream) {
        this.protocolVersion = messageStream.readInt();
        this.token = messageStream.readString();
        this.requestedCapabilities = messageStream.readLong();
        this.profileId = messageStream.readString();
        this.dataDirectory = messageStream.readString();
        this.workspaceDirectory = messageStream.readString();
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

    public String profileId() {
        return this.profileId;
    }

    public String dataDirectory() {
        return this.dataDirectory;
    }

    public String workspaceDirectory() {
        return this.workspaceDirectory;
    }
}
