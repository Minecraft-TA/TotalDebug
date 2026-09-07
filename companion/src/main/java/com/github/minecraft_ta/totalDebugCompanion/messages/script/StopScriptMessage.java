package com.github.minecraft_ta.totalDebugCompanion.messages.script;

import com.github.tth05.scnet.message.AbstractMessageOutgoing;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

public class StopScriptMessage extends AbstractMessageOutgoing {

    private final int scriptId;

    public StopScriptMessage(int scriptId) {
        this.scriptId = scriptId;
    }

    @Override
    public void write(ByteBufferOutputStream messageStream) {
        messageStream.writeInt(this.scriptId);
    }
}
