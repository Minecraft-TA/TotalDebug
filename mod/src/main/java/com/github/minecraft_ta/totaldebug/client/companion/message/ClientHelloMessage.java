package com.github.minecraft_ta.totaldebug.client.companion.message;

import com.github.tth05.scnet.message.AbstractMessageOutgoing;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

import java.util.Objects;

public final class ClientHelloMessage extends AbstractMessageOutgoing {
    private final int protocolVersion;
    private final String token;
    private final String profileId;
    private final String dataDirectory;
    private final String workspaceDirectory;

    public ClientHelloMessage(
            int protocolVersion,
            String token,
            String profileId,
            String dataDirectory,
            String workspaceDirectory
    ) {
        this.protocolVersion = protocolVersion;
        this.token = Objects.requireNonNull(token, "token");
        this.profileId = Objects.requireNonNull(profileId, "profileId");
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory");
        this.workspaceDirectory = Objects.requireNonNull(workspaceDirectory, "workspaceDirectory");
    }

    @Override
    public void write(ByteBufferOutputStream messageStream) {
        messageStream.writeInt(this.protocolVersion);
        messageStream.writeString(this.token);
        messageStream.writeString(this.profileId);
        messageStream.writeString(this.dataDirectory);
        messageStream.writeString(this.workspaceDirectory);
    }

}
