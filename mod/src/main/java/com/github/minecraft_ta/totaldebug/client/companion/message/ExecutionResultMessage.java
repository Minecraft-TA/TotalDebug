package com.github.minecraft_ta.totaldebug.client.companion.message;

import com.github.minecraft_ta.totaldebug.script.ExecutionResult;
import com.github.minecraft_ta.totaldebug.script.ExecutionResultCodec;
import com.github.tth05.scnet.message.AbstractMessageOutgoing;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

import java.util.Objects;

public final class ExecutionResultMessage extends AbstractMessageOutgoing {
    private final int scriptId;
    private final ExecutionResult result;

    public ExecutionResultMessage(int scriptId, ExecutionResult result) {
        this.scriptId = scriptId;
        this.result = Objects.requireNonNull(result, "result");
    }

    @Override
    public void write(ByteBufferOutputStream messageStream) {
        messageStream.writeInt(this.scriptId);
        messageStream.writeString(ExecutionResultCodec.encode(this.result).json());
    }

    public int scriptId() {
        return this.scriptId;
    }
}
