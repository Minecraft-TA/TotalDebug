package com.github.minecraft_ta.totaldebug.protocol.scnet.companion;

import com.github.minecraft_ta.totaldebug.protocol.message.RuntimeInventoryPayload;
import com.github.tth05.scnet.message.AbstractMessageIncoming;
import com.github.tth05.scnet.util.ByteBufferInputStream;

public final class RuntimeInventoryMessage extends AbstractMessageIncoming {
    public static final int PREPARING = RuntimeInventoryPayload.PREPARING;
    public static final int AVAILABLE = RuntimeInventoryPayload.AVAILABLE;
    public static final int FAILED = RuntimeInventoryPayload.FAILED;
    private RuntimeInventoryPayload payload;
    public RuntimeInventoryMessage() { }
    public RuntimeInventoryMessage(int state, String inventoryId, String inventoryFile, String detail) {
        this.payload = new RuntimeInventoryPayload(state, inventoryId, inventoryFile, detail);
    }
    @Override public void read(ByteBufferInputStream input) { this.payload = RuntimeInventoryPayload.read(input); }
    public int state() { return this.payload.state(); }
    public String inventoryId() { return this.payload.inventoryId(); }
    public String inventoryFile() { return this.payload.inventoryFile(); }
    public String detail() { return this.payload.detail(); }
}
