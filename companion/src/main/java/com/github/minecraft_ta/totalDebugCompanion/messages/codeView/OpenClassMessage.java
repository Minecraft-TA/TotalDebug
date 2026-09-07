package com.github.minecraft_ta.totalDebugCompanion.messages.codeView;

import com.github.minecraft_ta.totalDebugCompanion.CompanionApp;
import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

public final class OpenClassMessage extends AbstractMessage {
    private String binaryName;
    private int targetType;
    private String targetIdentifier;

    public OpenClassMessage() {
    }

    @Override
    public void write(ByteBufferOutputStream messageStream) {
        messageStream.writeString(this.binaryName);
        messageStream.writeInt(this.targetType);
        messageStream.writeString(this.targetIdentifier);
    }

    @Override
    public void read(ByteBufferInputStream messageStream) {
        this.binaryName = messageStream.readString();
        this.targetType = messageStream.readInt();
        this.targetIdentifier = messageStream.readString();
    }

    public static void handle(OpenClassMessage message) {
        CompanionApp.openClass(message.binaryName, message.targetType, message.targetIdentifier);
    }
}
