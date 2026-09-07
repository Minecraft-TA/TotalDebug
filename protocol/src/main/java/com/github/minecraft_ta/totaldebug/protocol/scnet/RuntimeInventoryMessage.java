package com.github.minecraft_ta.totaldebug.protocol.scnet;

import com.github.minecraft_ta.totaldebug.protocol.message.RuntimeInventoryPayload;
import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;
import java.util.Objects;

public final class RuntimeInventoryMessage extends AbstractMessage {
    public static final int PREPARING = RuntimeInventoryPayload.PREPARING;
    public static final int AVAILABLE = RuntimeInventoryPayload.AVAILABLE;
    public static final int FAILED = RuntimeInventoryPayload.FAILED;
    private RuntimeInventoryPayload payload;

    public RuntimeInventoryMessage() {
    }

    public RuntimeInventoryMessage(int state, String inventoryId, String inventoryFile, String detail) {
        this.payload = new RuntimeInventoryPayload(state, inventoryId, inventoryFile, detail);
    }

    public static RuntimeInventoryMessage preparing(String detail) {
        return new RuntimeInventoryMessage(PREPARING, "", "", Objects.requireNonNull(detail));
    }

    public static RuntimeInventoryMessage available(String id, String file) {
        return new RuntimeInventoryMessage(AVAILABLE, Objects.requireNonNull(id), Objects.requireNonNull(file), "");
    }

    public static RuntimeInventoryMessage failed(String detail) {
        return new RuntimeInventoryMessage(FAILED, "", "", Objects.requireNonNull(detail));
    }

    @Override
    public void read(ByteBufferInputStream input) {
        this.payload = RuntimeInventoryPayload.read(input);
    }

    @Override
    public void write(ByteBufferOutputStream output) {
        this.payload.write(output);
    }

    public int state() {
        return this.payload.state();
    }

    public String inventoryId() {
        return this.payload.inventoryId();
    }

    public String inventoryFile() {
        return this.payload.inventoryFile();
    }

    public String detail() {
        return this.payload.detail();
    }
}
