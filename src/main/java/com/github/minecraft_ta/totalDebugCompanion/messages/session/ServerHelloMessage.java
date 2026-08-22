package com.github.minecraft_ta.totalDebugCompanion.messages.session;

import com.github.minecraft_ta.totalDebugCompanion.session.CompanionProtocol;
import com.github.tth05.scnet.message.AbstractMessageOutgoing;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

import java.util.Objects;

public final class ServerHelloMessage extends AbstractMessageOutgoing {
    private final int protocolVersion;
    private final boolean accepted;
    private final long capabilities;
    private final String rejectionReason;

    private ServerHelloMessage(boolean accepted, long capabilities, String rejectionReason) {
        this.protocolVersion = CompanionProtocol.VERSION;
        this.accepted = accepted;
        this.capabilities = capabilities;
        this.rejectionReason = Objects.requireNonNull(rejectionReason, "rejectionReason");
    }

    public static ServerHelloMessage accepted(long capabilities) {
        return new ServerHelloMessage(true, capabilities, "");
    }

    public static ServerHelloMessage rejected(String reason) {
        return new ServerHelloMessage(false, 0, reason);
    }

    @Override
    public void write(ByteBufferOutputStream messageStream) {
        messageStream.writeInt(this.protocolVersion);
        messageStream.writeBoolean(this.accepted);
        messageStream.writeLong(this.capabilities);
        messageStream.writeString(this.rejectionReason);
    }

    public int protocolVersion() {
        return this.protocolVersion;
    }

    public boolean accepted() {
        return this.accepted;
    }

    public long capabilities() {
        return this.capabilities;
    }

    public String rejectionReason() {
        return this.rejectionReason;
    }
}
