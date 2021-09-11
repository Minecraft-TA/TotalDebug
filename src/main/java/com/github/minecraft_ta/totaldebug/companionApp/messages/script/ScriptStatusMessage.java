package com.github.minecraft_ta.totaldebug.companionApp.messages.script;

import com.github.tth05.scnet.message.AbstractMessageOutgoing;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

public class ScriptStatusMessage extends AbstractMessageOutgoing {

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

    public enum Type {
        COMPILATION_FAILED,
        RUN_EXCEPTION,
        RUN_COMPLETED;
    }
}
