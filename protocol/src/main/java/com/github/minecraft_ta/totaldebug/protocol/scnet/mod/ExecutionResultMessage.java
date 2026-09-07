package com.github.minecraft_ta.totaldebug.protocol.scnet.mod;

import com.github.minecraft_ta.totaldebug.protocol.message.ExecutionResultPayload;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionResult;
import com.github.tth05.scnet.message.AbstractMessageOutgoing;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

public final class ExecutionResultMessage extends AbstractMessageOutgoing {
    private final ExecutionResultPayload payload;
    public ExecutionResultMessage(int scriptId, ExecutionResult result) {
        this.payload = new ExecutionResultPayload(scriptId, result);
    }
    @Override public void write(ByteBufferOutputStream output) { this.payload.write(output); }
    public int scriptId() { return this.payload.scriptId(); }
    public ExecutionResult result() { return this.payload.result(); }
}
