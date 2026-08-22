package com.github.minecraft_ta.totaldebug.client.companion.message;

import com.github.tth05.scnet.message.AbstractMessageIncoming;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

public final class StopScriptMessage extends AbstractMessageIncoming {
    private int scriptId;

    public StopScriptMessage() {
    }

    @Override
    public void read(ByteBufferInputStream messageStream) {
        this.scriptId = messageStream.readInt();
    }

    @Override
    public void write(ByteBufferOutputStream messageStream) {
        messageStream.writeInt(this.scriptId);
    }

    public int scriptId() {
        return this.scriptId;
    }
}
