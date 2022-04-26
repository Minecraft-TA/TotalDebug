package com.github.minecraft_ta.totaldebug.companionApp.messages.codeView;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

import java.nio.file.Path;

public class DecompileOrOpenMessage extends AbstractMessage {

    private String name;
    private int targetType;
    private String targetIdentifier;

    public DecompileOrOpenMessage() {
    }

    public DecompileOrOpenMessage(Path filePath, int targetType, String targetIdentifier) {
        this.name = filePath.toAbsolutePath().toString();
        this.targetType = targetType;
        this.targetIdentifier = targetIdentifier == null ? "" : targetIdentifier;
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

    public static void handle(DecompileOrOpenMessage message) {
        try {
            TotalDebug.PROXY.getDecompilationManager().openGui(Class.forName(message.name), message.targetType, message.targetIdentifier);
        } catch (Throwable t) {
            TotalDebug.LOGGER.error("Received decompile request message for unknown class", t);
        }
    }
}
