package com.github.minecraft_ta.totaldebug.protocol.message;

import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

/** Protocol-11 payload. Field order and encoding are shared by both endpoints. */
public record DebugTargetPayload(String targetId, String displayName, byte targetKind, long processId) {
    public static final byte LOCAL_JVM = 1;

    public static DebugTargetPayload read(ByteBufferInputStream input) { return new DebugTargetPayload(input.readString(), input.readString(), input.readByte(), input.readLong()); }
    public void write(ByteBufferOutputStream output) {
        output.writeString(this.targetId);
        output.writeString(this.displayName);
        output.writeByte(this.targetKind);
        output.writeLong(this.processId);
    }
}
