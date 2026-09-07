package com.github.minecraft_ta.totaldebug.protocol.scnet.companion;

import com.github.minecraft_ta.totaldebug.protocol.message.DebugTargetPayload;
import com.github.tth05.scnet.message.AbstractMessageIncoming;
import com.github.tth05.scnet.util.ByteBufferInputStream;

public final class DebugTargetMessage extends AbstractMessageIncoming {
    public static final byte LOCAL_JVM = DebugTargetPayload.LOCAL_JVM;
    private DebugTargetPayload payload;
    public DebugTargetMessage() { }
    public DebugTargetMessage(String targetId, String displayName, byte targetKind, long processId) {
        this.payload = new DebugTargetPayload(targetId, displayName, targetKind, processId);
    }
    @Override public void read(ByteBufferInputStream input) { this.payload = DebugTargetPayload.read(input); }
    public String targetId() { return this.payload.targetId(); }
    public String displayName() { return this.payload.displayName(); }
    public byte targetKind() { return this.payload.targetKind(); }
    public long processId() { return this.payload.processId(); }
}
