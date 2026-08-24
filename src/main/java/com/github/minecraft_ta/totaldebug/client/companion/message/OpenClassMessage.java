package com.github.minecraft_ta.totaldebug.client.companion.message;

import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

import java.util.Objects;

public final class OpenClassMessage extends AbstractMessage {
    private String binaryName;
    private int targetType;
    private String targetIdentifier;

    public OpenClassMessage() {
    }

    public OpenClassMessage(String binaryName, int targetType, String targetIdentifier) {
        this.binaryName = Objects.requireNonNull(binaryName, "binaryName");
        this.targetType = targetType;
        this.targetIdentifier = Objects.requireNonNullElse(targetIdentifier, "");
    }

    @Override
    public void read(ByteBufferInputStream messageStream) {
        this.binaryName = messageStream.readString();
        this.targetType = messageStream.readInt();
        this.targetIdentifier = messageStream.readString();
    }

    @Override
    public void write(ByteBufferOutputStream messageStream) {
        messageStream.writeString(this.binaryName);
        messageStream.writeInt(this.targetType);
        messageStream.writeString(this.targetIdentifier);
    }

    public String binaryName() {
        return this.binaryName;
    }

    public int targetType() {
        return this.targetType;
    }

    public String targetIdentifier() {
        return this.targetIdentifier;
    }
}
