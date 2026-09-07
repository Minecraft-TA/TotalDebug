package com.github.minecraft_ta.totaldebug.protocol.message;

import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

/** Protocol-11 payload. Field order and encoding are shared by both endpoints. */
public record StopScriptPayload(int scriptId) {
    public static StopScriptPayload read(ByteBufferInputStream input) { return new StopScriptPayload(input.readInt()); }
    public void write(ByteBufferOutputStream output) {
        output.writeInt(this.scriptId);
    }
}
