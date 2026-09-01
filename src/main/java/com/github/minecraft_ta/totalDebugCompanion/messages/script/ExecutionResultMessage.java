package com.github.minecraft_ta.totalDebugCompanion.messages.script;

import com.github.minecraft_ta.totalDebugCompanion.script.ExecutionResult;
import com.github.tth05.scnet.message.AbstractMessageIncoming;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

public class ExecutionResultMessage extends AbstractMessageIncoming {

    private int scriptId;
    private ExecutionResult result;

    public ExecutionResultMessage() {
    }

    public ExecutionResultMessage(int scriptId, ExecutionResult result) {
        this.scriptId = scriptId;
        this.result = java.util.Objects.requireNonNull(result, "result");
    }

    @Override
    public void read(ByteBufferInputStream messageStream) {
        this.scriptId = messageStream.readInt();
        this.result = ExecutionResult.parse(messageStream.readString());
    }

    @Override
    public void write(ByteBufferOutputStream messageStream) {
        messageStream.writeInt(this.scriptId);
        messageStream.writeString(this.result.toJson());
    }

    public int getScriptId() {
        return scriptId;
    }

    public ExecutionResult getResult() {
        return this.result;
    }
}
