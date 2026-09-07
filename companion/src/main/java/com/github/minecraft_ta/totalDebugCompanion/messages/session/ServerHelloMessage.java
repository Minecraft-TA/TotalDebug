package com.github.minecraft_ta.totalDebugCompanion.messages.session;

import com.github.minecraft_ta.totalDebugCompanion.session.CompanionProtocol;
import com.github.tth05.scnet.message.AbstractMessageOutgoing;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

import java.util.Objects;

public final class ServerHelloMessage extends AbstractMessageOutgoing {
    private final int protocolVersion;
    private final boolean accepted;
    private final String rejectionReason;

    private ServerHelloMessage(boolean accepted, String rejectionReason) {
        this.protocolVersion = CompanionProtocol.VERSION;
        this.accepted = accepted;
        this.rejectionReason = Objects.requireNonNull(rejectionReason, "rejectionReason");
    }

    public static ServerHelloMessage accept() {
        return new ServerHelloMessage(true, "");
    }

    public static ServerHelloMessage rejected(String reason) {
        return new ServerHelloMessage(false, reason);
    }

    @Override
    public void write(ByteBufferOutputStream messageStream) {
        messageStream.writeInt(this.protocolVersion);
        messageStream.writeBoolean(this.accepted);
        messageStream.writeString(this.rejectionReason);
    }

    public boolean accepted() {
        return this.accepted;
    }

    public String rejectionReason() {
        return this.rejectionReason;
    }
}
