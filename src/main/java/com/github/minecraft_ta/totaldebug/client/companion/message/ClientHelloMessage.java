package com.github.minecraft_ta.totaldebug.client.companion.message;

import com.github.tth05.scnet.message.AbstractMessageOutgoing;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

import java.util.Objects;

public final class ClientHelloMessage extends AbstractMessageOutgoing {
    private final int protocolVersion;
    private final String token;
    private final long requestedCapabilities;
    private final String profileId;
    private final String dataDirectory;
    private final String indexFile;
    private final String workspaceDirectory;
    private final String runtimeSourceManifest;
    private final String runtimeSignature;

    public ClientHelloMessage(
            int protocolVersion,
            String token,
            long requestedCapabilities,
            String profileId,
            String dataDirectory,
            String indexFile,
            String workspaceDirectory,
            String runtimeSourceManifest,
            String runtimeSignature
    ) {
        this.protocolVersion = protocolVersion;
        this.token = Objects.requireNonNull(token, "token");
        this.requestedCapabilities = requestedCapabilities;
        this.profileId = Objects.requireNonNull(profileId, "profileId");
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory");
        this.indexFile = Objects.requireNonNull(indexFile, "indexFile");
        this.workspaceDirectory = Objects.requireNonNull(workspaceDirectory, "workspaceDirectory");
        this.runtimeSourceManifest = Objects.requireNonNull(runtimeSourceManifest, "runtimeSourceManifest");
        this.runtimeSignature = Objects.requireNonNull(runtimeSignature, "runtimeSignature");
    }

    @Override
    public void write(ByteBufferOutputStream messageStream) {
        messageStream.writeInt(this.protocolVersion);
        messageStream.writeString(this.token);
        messageStream.writeLong(this.requestedCapabilities);
        messageStream.writeString(this.profileId);
        messageStream.writeString(this.dataDirectory);
        messageStream.writeString(this.indexFile);
        messageStream.writeString(this.workspaceDirectory);
        messageStream.writeString(this.runtimeSourceManifest);
        messageStream.writeString(this.runtimeSignature);
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
