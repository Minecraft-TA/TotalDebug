package com.github.minecraft_ta.totaldebug.protocol.scnet;

import com.github.minecraft_ta.totaldebug.protocol.message.DebugTargetPayload;
import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;
import java.util.Objects;

public final class DebugTargetMessage extends AbstractMessage {
    public static final byte LOCAL_JVM = DebugTargetPayload.LOCAL_JVM;
    private DebugTargetPayload payload;

    public DebugTargetMessage() {
    }

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

        this.payload = new DebugTargetPayload(targetId, displayName, targetKind, processId);
    }

    @Override
    public void read(ByteBufferInputStream input) {
        this.payload = DebugTargetPayload.read(input);
    }

    @Override
    public void write(ByteBufferOutputStream output) {
        this.payload.write(output);
    }

    public String targetId() {
        return this.payload.targetId();
    }

    public String displayName() {
        return this.payload.displayName();
    }

    public byte targetKind() {
        return this.payload.targetKind();
    }

    public long processId() {
        return this.payload.processId();
    }
}
