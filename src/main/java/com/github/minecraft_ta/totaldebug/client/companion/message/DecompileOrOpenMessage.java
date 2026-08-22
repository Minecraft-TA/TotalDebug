package com.github.minecraft_ta.totaldebug.client.companion.message;

import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

import java.nio.file.Path;
import java.util.Objects;

public final class DecompileOrOpenMessage extends AbstractMessage {
    private String name;
    private int targetType;
    private String targetIdentifier;

    public DecompileOrOpenMessage() {
    }

    public DecompileOrOpenMessage(Path filePath) {
        this(filePath.toAbsolutePath().normalize().toString(), -1, "");
    }

    public DecompileOrOpenMessage(String name, int targetType, String targetIdentifier) {
        this.name = Objects.requireNonNull(name, "name");
        this.targetType = targetType;
        this.targetIdentifier = Objects.requireNonNullElse(targetIdentifier, "");
    }

    @Override
    public void read(ByteBufferInputStream messageStream) {
        this.name = messageStream.readString();
        this.targetType = messageStream.readInt();
        this.targetIdentifier = messageStream.readString();
    }

    @Override
    public void write(ByteBufferOutputStream messageStream) {
        messageStream.writeString(this.name);
        messageStream.writeInt(this.targetType);
        messageStream.writeString(this.targetIdentifier);
    }

    public String name() {
        return this.name;
    }

    public int targetType() {
        return this.targetType;
    }

    public String targetIdentifier() {
        return this.targetIdentifier;
    }
}
