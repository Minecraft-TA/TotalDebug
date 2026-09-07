package com.github.minecraft_ta.totaldebug.protocol.message;

import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

/** Protocol-11 payload. Field order and encoding are shared by both endpoints. */
public record OpenClassPayload(String binaryName, int targetType, String targetIdentifier) {
    public static OpenClassPayload read(ByteBufferInputStream input) { return new OpenClassPayload(input.readString(), input.readInt(), input.readString()); }
    public void write(ByteBufferOutputStream output) {
        output.writeString(this.binaryName);
        output.writeInt(this.targetType);
        output.writeString(this.targetIdentifier);
    }
}
