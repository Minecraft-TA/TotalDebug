package com.github.minecraft_ta.totaldebug.protocol.scnet.mod;

import com.github.minecraft_ta.totaldebug.protocol.message.ReadyPayload;
import com.github.tth05.scnet.message.AbstractMessageIncoming;
import com.github.tth05.scnet.util.ByteBufferInputStream;

public final class CompanionReadyMessage extends AbstractMessageIncoming {
    private ReadyPayload payload;
    public CompanionReadyMessage() { this.payload = new ReadyPayload(); }
    @Override public void read(ByteBufferInputStream input) { this.payload = ReadyPayload.read(input); }

}
