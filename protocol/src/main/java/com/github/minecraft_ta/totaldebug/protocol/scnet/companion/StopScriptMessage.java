package com.github.minecraft_ta.totaldebug.protocol.scnet.companion;

import com.github.minecraft_ta.totaldebug.protocol.message.StopScriptPayload;
import com.github.tth05.scnet.message.AbstractMessageOutgoing;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

public final class StopScriptMessage extends AbstractMessageOutgoing {
    private final StopScriptPayload payload;
    public StopScriptMessage(int scriptId) {
        this.payload = new StopScriptPayload(scriptId);
    }
    @Override public void write(ByteBufferOutputStream output) { this.payload.write(output); }
    public int scriptId() { return this.payload.scriptId(); }
}
