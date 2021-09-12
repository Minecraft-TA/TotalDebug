package com.github.minecraft_ta.totaldebug.companionApp.messages.script;

import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

public class ScriptStatusMessage extends AbstractMessage {

    private int scriptId;
    private Type type;
    private String message;

    public ScriptStatusMessage()  {}

    public ScriptStatusMessage(int scriptId, Type type, String message) {
        this.scriptId = scriptId;
        this.type = type;
        this.message = message == null ? "" : message;
    }

    @Override
    public void write(ByteBufferOutputStream messageStream) {
        messageStream.writeInt(this.scriptId);
        messageStream.writeString(this.type.name());
        messageStream.writeString(this.message);
    }

    @Override
    public void read(ByteBufferInputStream messageStream) {
        this.scriptId = messageStream.readInt();
        this.type = Type.valueOf(messageStream.readString());
        this.message = messageStream.readString();
    }

    public enum Type {
        COMPILATION_FAILED,
        COMPILATION_COMPLETED,
        RUN_EXCEPTION,
        RUN_COMPLETED;
    }
}
