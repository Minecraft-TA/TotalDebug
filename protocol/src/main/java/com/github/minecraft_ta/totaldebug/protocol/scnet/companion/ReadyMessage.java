package com.github.minecraft_ta.totaldebug.protocol.scnet.companion;

import com.github.minecraft_ta.totaldebug.protocol.message.ReadyPayload;
import com.github.tth05.scnet.message.AbstractMessageOutgoing;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

public final class ReadyMessage extends AbstractMessageOutgoing {
    private final ReadyPayload payload;
    public ReadyMessage() { this.payload = new ReadyPayload(); }
    @Override public void write(ByteBufferOutputStream output) { this.payload.write(output); }

}
