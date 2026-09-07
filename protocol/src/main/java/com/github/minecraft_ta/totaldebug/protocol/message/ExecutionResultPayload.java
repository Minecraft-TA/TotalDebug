package com.github.minecraft_ta.totaldebug.protocol.message;

import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionResult;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionResultCodec;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

/** Protocol-11 payload. Field order and encoding are shared by both endpoints. */
public record ExecutionResultPayload(int scriptId, ExecutionResult result) {
    public static ExecutionResultPayload read(ByteBufferInputStream input) { return new ExecutionResultPayload(input.readInt(), ExecutionResultCodec.decode(input.readString())); }
    public void write(ByteBufferOutputStream output) {
        output.writeInt(this.scriptId);
        output.writeString(ExecutionResultCodec.encode(this.result).json());
    }
}
