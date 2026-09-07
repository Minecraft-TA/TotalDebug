package com.github.minecraft_ta.totaldebug.protocol.scnet.mod;

import com.github.minecraft_ta.totaldebug.protocol.message.ServerHelloPayload;
import com.github.tth05.scnet.message.AbstractMessageIncoming;
import com.github.tth05.scnet.util.ByteBufferInputStream;

public final class ServerHelloMessage extends AbstractMessageIncoming {
    private ServerHelloPayload payload;
    public ServerHelloMessage() { }
    public ServerHelloMessage(int protocolVersion, boolean accepted, String rejectionReason) {
        this.payload = new ServerHelloPayload(protocolVersion, accepted, rejectionReason);
    }
    @Override public void read(ByteBufferInputStream input) { this.payload = ServerHelloPayload.read(input); }
    public int protocolVersion() { return this.payload.protocolVersion(); }
    public boolean accepted() { return this.payload.accepted(); }
    public String rejectionReason() { return this.payload.rejectionReason(); }
}
