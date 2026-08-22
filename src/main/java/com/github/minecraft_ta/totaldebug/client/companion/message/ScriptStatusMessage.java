package com.github.minecraft_ta.totaldebug.client.companion.message;

import com.github.minecraft_ta.totaldebug.script.ScriptStatusType;
import com.github.tth05.scnet.message.AbstractMessageOutgoing;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

import java.util.Objects;

public final class ScriptStatusMessage extends AbstractMessageOutgoing {
    private final int scriptId;
    private final ScriptStatusType type;
    private final String message;

    public ScriptStatusMessage(int scriptId, ScriptStatusType type, String message) {
        this.scriptId = scriptId;
        this.type = Objects.requireNonNull(type, "type");
        this.message = Objects.requireNonNullElse(message, "");
    }

    @Override
    public void write(ByteBufferOutputStream messageStream) {
        messageStream.writeInt(this.scriptId);
        messageStream.writeString(this.type.name());
        messageStream.writeString(this.message);
    }

    public int scriptId() {
        return this.scriptId;
    }

    public ScriptStatusType type() {
        return this.type;
    }

    public String message() {
        return this.message;
    }
}
