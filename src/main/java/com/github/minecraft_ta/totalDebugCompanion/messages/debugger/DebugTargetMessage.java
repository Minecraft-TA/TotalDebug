package com.github.minecraft_ta.totalDebugCompanion.messages.debugger;

import com.github.tth05.scnet.message.AbstractMessageIncoming;
import com.github.tth05.scnet.util.ByteBufferInputStream;

public final class DebugTargetMessage extends AbstractMessageIncoming {
    public static final byte LOCAL_JVM = 1;

    private String targetId;
    private String displayName;
    private byte targetKind;
    private long processId;

    @Override
    public void read(ByteBufferInputStream messageStream) {
        this.targetId = messageStream.readString();
        this.displayName = messageStream.readString();
        this.targetKind = messageStream.readByte();
        this.processId = messageStream.readLong();
    }

    public String targetId() {
        return this.targetId;
    }

    public String displayName() {
        return this.displayName;
    }

    public byte targetKind() {
        return this.targetKind;
    }

    public long processId() {
        return this.processId;
    }
}
