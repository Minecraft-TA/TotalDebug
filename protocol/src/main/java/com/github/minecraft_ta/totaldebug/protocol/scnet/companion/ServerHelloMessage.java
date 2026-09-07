package com.github.minecraft_ta.totaldebug.protocol.scnet.companion;

import com.github.minecraft_ta.totaldebug.protocol.CompanionProtocol;
import com.github.minecraft_ta.totaldebug.protocol.message.ServerHelloPayload;
import com.github.tth05.scnet.message.AbstractMessageOutgoing;
import com.github.tth05.scnet.util.ByteBufferOutputStream;
import java.util.Objects;

public final class ServerHelloMessage extends AbstractMessageOutgoing {
    private final ServerHelloPayload payload;
    public ServerHelloMessage(int protocolVersion, boolean accepted, String rejectionReason) {
        this.payload = new ServerHelloPayload(protocolVersion, accepted, rejectionReason);
    }
    public static ServerHelloMessage accept() { return new ServerHelloMessage(CompanionProtocol.VERSION, true, ""); }
    public static ServerHelloMessage rejected(String reason) { return new ServerHelloMessage(CompanionProtocol.VERSION, false, Objects.requireNonNull(reason)); }
    @Override public void write(ByteBufferOutputStream output) { this.payload.write(output); }
    public int protocolVersion() { return this.payload.protocolVersion(); }
    public boolean accepted() { return this.payload.accepted(); }
    public String rejectionReason() { return this.payload.rejectionReason(); }
}
