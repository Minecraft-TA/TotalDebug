package com.github.minecraft_ta.totaldebug.protocol.message;

import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

/** Protocol-11 payload. Field order and encoding are shared by both endpoints. */
public record RunScriptPayload(int scriptId, String scriptText, boolean serverSide, String executionEnvironment) {
    public static RunScriptPayload read(ByteBufferInputStream input) { return new RunScriptPayload(input.readInt(), input.readString(), input.readBoolean(), input.readString()); }
    public void write(ByteBufferOutputStream output) {
        output.writeInt(this.scriptId);
        output.writeString(this.scriptText);
        output.writeBoolean(this.serverSide);
        output.writeString(this.executionEnvironment);
    }
}
