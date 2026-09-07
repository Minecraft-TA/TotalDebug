package com.github.minecraft_ta.totaldebug.protocol.scnet.mod;

import com.github.minecraft_ta.totaldebug.protocol.message.StopScriptPayload;
import com.github.tth05.scnet.message.AbstractMessageIncoming;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

public final class StopScriptMessage extends AbstractMessageIncoming {
    private StopScriptPayload payload;
    public StopScriptMessage() { }
    public StopScriptMessage(int scriptId) {
        this.payload = new StopScriptPayload(scriptId);
    }
    @Override public void read(ByteBufferInputStream input) { this.payload = StopScriptPayload.read(input); }
    @Override public void write(ByteBufferOutputStream output) { this.payload.write(output); }
    public int scriptId() { return this.payload.scriptId(); }
}
