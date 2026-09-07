package com.github.minecraft_ta.totaldebug.protocol.scnet.companion;

import com.github.minecraft_ta.totaldebug.protocol.message.ExecutionResultPayload;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionResult;
import com.github.tth05.scnet.message.AbstractMessageIncoming;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

public final class ExecutionResultMessage extends AbstractMessageIncoming {
    private ExecutionResultPayload payload;
    public ExecutionResultMessage() { }
    public ExecutionResultMessage(int scriptId, ExecutionResult result) {
        this.payload = new ExecutionResultPayload(scriptId, result);
    }
    @Override public void read(ByteBufferInputStream input) { this.payload = ExecutionResultPayload.read(input); }
    @Override public void write(ByteBufferOutputStream output) { this.payload.write(output); }
    public int getScriptId() { return this.payload.scriptId(); }
    public ExecutionResult getResult() { return this.payload.result(); }
}
