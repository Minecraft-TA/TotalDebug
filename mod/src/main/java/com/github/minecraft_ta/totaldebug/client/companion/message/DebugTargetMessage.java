package com.github.minecraft_ta.totaldebug.client.companion.message;

import com.github.tth05.scnet.message.AbstractMessageOutgoing;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

import java.util.Objects;

public final class DebugTargetMessage extends AbstractMessageOutgoing {
    public static final byte LOCAL_JVM = 1;

    private final String targetId;
    private final String displayName;
    private final byte targetKind;
    private final long processId;

    public DebugTargetMessage(String targetId, String displayName, byte targetKind, long processId) {
        if (Objects.requireNonNull(targetId, "targetId").isBlank()) {
            throw new IllegalArgumentException("Debug target id is blank");
        }
        if (Objects.requireNonNull(displayName, "displayName").isBlank()) {
            throw new IllegalArgumentException("Debug target display name is blank");
        }
        if (targetKind != LOCAL_JVM) {
            throw new IllegalArgumentException("Unknown debug target kind: " + targetKind);
        }
        if (processId < 1) {
            throw new IllegalArgumentException("Debug target process id must be positive");
        }
        this.targetId = targetId;
        this.displayName = displayName;
        this.targetKind = targetKind;
        this.processId = processId;
    }

    @Override
    public void write(ByteBufferOutputStream messageStream) {
        messageStream.writeString(this.targetId);
        messageStream.writeString(this.displayName);
        messageStream.writeByte(this.targetKind);
        messageStream.writeLong(this.processId);
    }

}
