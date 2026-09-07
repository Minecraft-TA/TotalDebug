package com.github.minecraft_ta.totaldebug.protocol.scnet.mod;

import com.github.minecraft_ta.totaldebug.protocol.message.RetryRuntimeInventoryPayload;
import com.github.tth05.scnet.message.AbstractMessageIncoming;
import com.github.tth05.scnet.util.ByteBufferInputStream;

public final class RetryRuntimeInventoryMessage extends AbstractMessageIncoming {
    private RetryRuntimeInventoryPayload payload;
    public RetryRuntimeInventoryMessage() { this.payload = new RetryRuntimeInventoryPayload(); }
    @Override public void read(ByteBufferInputStream input) { this.payload = RetryRuntimeInventoryPayload.read(input); }

}
