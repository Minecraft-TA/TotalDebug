package com.github.minecraft_ta.totaldebug.protocol.scnet.mod;

import com.github.minecraft_ta.totaldebug.protocol.message.FocusWindowPayload;
import com.github.tth05.scnet.message.AbstractMessageOutgoing;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

public final class FocusWindowMessage extends AbstractMessageOutgoing {
    private final FocusWindowPayload payload;
    public FocusWindowMessage() { this.payload = new FocusWindowPayload(); }
    @Override public void write(ByteBufferOutputStream output) { this.payload.write(output); }

}
