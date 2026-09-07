package com.github.minecraft_ta.totaldebug.protocol.scnet.companion;

import com.github.minecraft_ta.totaldebug.protocol.message.RetryRuntimeInventoryPayload;
import com.github.tth05.scnet.message.AbstractMessageOutgoing;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

public final class RetryRuntimeInventoryMessage extends AbstractMessageOutgoing {
    private final RetryRuntimeInventoryPayload payload;
    public RetryRuntimeInventoryMessage() { this.payload = new RetryRuntimeInventoryPayload(); }
    @Override public void write(ByteBufferOutputStream output) { this.payload.write(output); }

}
