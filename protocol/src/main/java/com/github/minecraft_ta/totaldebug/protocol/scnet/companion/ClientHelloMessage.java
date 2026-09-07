package com.github.minecraft_ta.totaldebug.protocol.scnet.companion;

import com.github.minecraft_ta.totaldebug.protocol.message.ClientHelloPayload;
import com.github.tth05.scnet.message.AbstractMessageIncoming;
import com.github.tth05.scnet.util.ByteBufferInputStream;

public final class ClientHelloMessage extends AbstractMessageIncoming {
    private ClientHelloPayload payload;
    public ClientHelloMessage() { }
    public ClientHelloMessage(int protocolVersion, String token, String profileId, String dataDirectory, String workspaceDirectory) {
        this.payload = new ClientHelloPayload(protocolVersion, token, profileId, dataDirectory, workspaceDirectory);
    }
    @Override public void read(ByteBufferInputStream input) { this.payload = ClientHelloPayload.read(input); }
    public int protocolVersion() { return this.payload.protocolVersion(); }
    public String token() { return this.payload.token(); }
    public String profileId() { return this.payload.profileId(); }
    public String dataDirectory() { return this.payload.dataDirectory(); }
    public String workspaceDirectory() { return this.payload.workspaceDirectory(); }
}
