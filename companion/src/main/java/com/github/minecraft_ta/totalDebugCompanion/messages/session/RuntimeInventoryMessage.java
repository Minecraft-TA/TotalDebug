package com.github.minecraft_ta.totalDebugCompanion.messages.session;

import com.github.tth05.scnet.message.AbstractMessageIncoming;
import com.github.tth05.scnet.util.ByteBufferInputStream;

public final class RuntimeInventoryMessage extends AbstractMessageIncoming {
    public static final int PREPARING = 0;
    public static final int AVAILABLE = 1;
    public static final int FAILED = 2;

    private int state;
    private String inventoryId;
    private String inventoryFile;
    private String detail;

    @Override
    public void read(ByteBufferInputStream messageStream) {
        this.state = messageStream.readInt();
        this.inventoryId = messageStream.readString();
        this.inventoryFile = messageStream.readString();
        this.detail = messageStream.readString();
    }

    public int state() {
        return this.state;
    }

    public String inventoryId() {
        return this.inventoryId;
    }

    public String inventoryFile() {
        return this.inventoryFile;
    }

    public String detail() {
        return this.detail;
    }
}
