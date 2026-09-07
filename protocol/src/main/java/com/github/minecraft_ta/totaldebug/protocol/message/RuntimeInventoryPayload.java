package com.github.minecraft_ta.totaldebug.protocol.message;

import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

/** Protocol-11 payload. Field order and encoding are shared by both endpoints. */
public record RuntimeInventoryPayload(int state, String inventoryId, String inventoryFile, String detail) {
    public static final int PREPARING = 0;
    public static final int AVAILABLE = 1;
    public static final int FAILED = 2;

    public static RuntimeInventoryPayload read(ByteBufferInputStream input) { return new RuntimeInventoryPayload(input.readInt(), input.readString(), input.readString(), input.readString()); }
    public void write(ByteBufferOutputStream output) {
        output.writeInt(this.state);
        output.writeString(this.inventoryId);
        output.writeString(this.inventoryFile);
        output.writeString(this.detail);
    }
}
