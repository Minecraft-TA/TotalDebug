package com.github.minecraft_ta.totaldebug.protocol.scnet.mod;

import com.github.minecraft_ta.totaldebug.protocol.message.ClientHelloPayload;
import com.github.tth05.scnet.message.AbstractMessageOutgoing;
import com.github.tth05.scnet.util.ByteBufferOutputStream;
import java.util.Objects;

public final class ClientHelloMessage extends AbstractMessageOutgoing {
    private final ClientHelloPayload payload;
    public ClientHelloMessage(int protocolVersion, String token, String profileId, String dataDirectory, String workspaceDirectory) {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(dataDirectory, "dataDirectory");
        Objects.requireNonNull(workspaceDirectory, "workspaceDirectory");
        this.payload = new ClientHelloPayload(protocolVersion, token, profileId, dataDirectory, workspaceDirectory);
    }
    @Override public void write(ByteBufferOutputStream output) { this.payload.write(output); }
    public int protocolVersion() { return this.payload.protocolVersion(); }
    public String token() { return this.payload.token(); }
    public String profileId() { return this.payload.profileId(); }
    public String dataDirectory() { return this.payload.dataDirectory(); }
    public String workspaceDirectory() { return this.payload.workspaceDirectory(); }
}
