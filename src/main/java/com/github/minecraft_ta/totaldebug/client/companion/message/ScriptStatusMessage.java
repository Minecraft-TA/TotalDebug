package com.github.minecraft_ta.totaldebug.client.companion.message;

import com.github.minecraft_ta.totaldebug.script.ScriptStatus;
import com.github.tth05.scnet.message.AbstractMessageOutgoing;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

import java.util.Objects;

public final class ScriptStatusMessage extends AbstractMessageOutgoing {
    private final int scriptId;
    private final ScriptStatus status;

    public ScriptStatusMessage(int scriptId, ScriptStatus status) {
        this.scriptId = scriptId;
        this.status = Objects.requireNonNull(status, "status");
    }

    @Override
    public void write(ByteBufferOutputStream messageStream) {
        messageStream.writeInt(this.scriptId);
        messageStream.writeString(this.status.type().name());
        messageStream.writeString(this.status.output());
        messageStream.writeBoolean(this.status.resultJson() != null);
        if (this.status.resultJson() != null) {
            messageStream.writeString(this.status.resultJson());
        }
        messageStream.writeString(this.status.error());
    }

    public int scriptId() {
        return this.scriptId;
    }

    public ScriptStatus status() {
        return this.status;
    }
}
