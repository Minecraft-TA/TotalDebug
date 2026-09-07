package com.github.minecraft_ta.totaldebug.protocol.message;

import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

/** Protocol-11 payload. Field order and encoding are shared by both endpoints. */
public record ServerHelloPayload(int protocolVersion, boolean accepted, String rejectionReason) {
    public static ServerHelloPayload read(ByteBufferInputStream input) { return new ServerHelloPayload(input.readInt(), input.readBoolean(), input.readString()); }
    public void write(ByteBufferOutputStream output) {
        output.writeInt(this.protocolVersion);
        output.writeBoolean(this.accepted);
        output.writeString(this.rejectionReason);
    }
}
