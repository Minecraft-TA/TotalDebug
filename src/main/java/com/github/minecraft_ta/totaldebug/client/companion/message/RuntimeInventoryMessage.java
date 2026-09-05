package com.github.minecraft_ta.totaldebug.client.companion.message;

import com.github.tth05.scnet.message.AbstractMessageOutgoing;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

import java.util.Objects;

public final class RuntimeInventoryMessage extends AbstractMessageOutgoing {
    public static final int PREPARING = 0;
    public static final int AVAILABLE = 1;
    public static final int FAILED = 2;

    private final int state;
    private final String inventoryId;
    private final String inventoryFile;
    private final String detail;

    private RuntimeInventoryMessage(int state, String inventoryId, String inventoryFile, String detail) {
        this.state = state;
        this.inventoryId = Objects.requireNonNull(inventoryId, "inventoryId");
        this.inventoryFile = Objects.requireNonNull(inventoryFile, "inventoryFile");
        this.detail = Objects.requireNonNull(detail, "detail");
    }

    public static RuntimeInventoryMessage preparing(String detail) {
        return new RuntimeInventoryMessage(PREPARING, "", "", detail);
    }

    public static RuntimeInventoryMessage available(String inventoryId, String inventoryFile) {
        return new RuntimeInventoryMessage(AVAILABLE, inventoryId, inventoryFile, "");
    }

    public static RuntimeInventoryMessage failed(String detail) {
        return new RuntimeInventoryMessage(FAILED, "", "", detail);
    }

    @Override
    public void write(ByteBufferOutputStream messageStream) {
        messageStream.writeInt(this.state);
        messageStream.writeString(this.inventoryId);
        messageStream.writeString(this.inventoryFile);
        messageStream.writeString(this.detail);
    }

    public int state() {
        return this.state;
    }

}
