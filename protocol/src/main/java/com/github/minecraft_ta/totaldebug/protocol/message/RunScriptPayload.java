package com.github.minecraft_ta.totaldebug.protocol.message;

import com.github.minecraft_ta.totaldebug.protocol.execution.ScriptBytecode;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

/** Field order and encoding are shared by both endpoints. */
public record RunScriptPayload(int scriptId, ScriptBytecode bytecode, String inventoryId, boolean serverSide, String executionEnvironment) {
    public static RunScriptPayload read(ByteBufferInputStream input) {
        return new RunScriptPayload(input.readInt(), ScriptBytecode.read(input), input.readString(), input.readBoolean(), input.readString());
    }
    public void write(ByteBufferOutputStream output) {
        output.writeInt(this.scriptId);
        this.bytecode.write(output);
        output.writeString(this.inventoryId);
        output.writeBoolean(this.serverSide);
        output.writeString(this.executionEnvironment);
    }
}
