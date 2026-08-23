package com.github.minecraft_ta.totalDebugCompanion.messages.session;

import com.github.tth05.scnet.message.AbstractMessageIncoming;
import com.github.tth05.scnet.util.ByteBufferInputStream;

public final class ClientHelloMessage extends AbstractMessageIncoming {
    private int protocolVersion;
    private String token;
    private long requestedCapabilities;
    private String profileId;
    private String dataDirectory;
    private String indexFile;
    private String workspaceDirectory;
    private String runtimeSourceManifest;
    private String runtimeSignature;

    @Override
    public void read(ByteBufferInputStream messageStream) {
        this.protocolVersion = messageStream.readInt();
        this.token = messageStream.readString();
        this.requestedCapabilities = messageStream.readLong();
        this.profileId = messageStream.readString();
        this.dataDirectory = messageStream.readString();
        this.indexFile = messageStream.readString();
        this.workspaceDirectory = messageStream.readString();
        this.runtimeSourceManifest = messageStream.readString();
        this.runtimeSignature = messageStream.readString();
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

    public String indexFile() {
        return this.indexFile;
    }

    public String workspaceDirectory() {
        return this.workspaceDirectory;
    }

    public String runtimeSourceManifest() {
        return this.runtimeSourceManifest;
    }

    public String runtimeSignature() {
        return this.runtimeSignature;
    }
}
