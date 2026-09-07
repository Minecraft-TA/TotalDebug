package com.github.minecraft_ta.totaldebug.protocol.scnet.companion;

import com.github.minecraft_ta.totaldebug.protocol.message.FocusWindowPayload;
import com.github.tth05.scnet.message.AbstractMessageIncoming;
import com.github.tth05.scnet.util.ByteBufferInputStream;

public final class FocusWindowMessage extends AbstractMessageIncoming {
    private FocusWindowPayload payload;
    public FocusWindowMessage() { this.payload = new FocusWindowPayload(); }
    @Override public void read(ByteBufferInputStream input) { this.payload = FocusWindowPayload.read(input); }

}
