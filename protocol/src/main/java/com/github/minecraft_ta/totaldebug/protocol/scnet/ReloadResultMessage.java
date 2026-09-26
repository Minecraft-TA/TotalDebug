package com.github.minecraft_ta.totaldebug.protocol.scnet;

import com.github.minecraft_ta.totaldebug.protocol.message.ReloadResultPayload;
import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

/** The game answers a {@link ReloadMessage}. */
public final class ReloadResultMessage extends AbstractMessage {
    private ReloadResultPayload payload;

    public ReloadResultMessage() {
    }

    public ReloadResultMessage(ReloadResultPayload payload) {
        this.payload = payload;
    }

    @Override
    public void read(ByteBufferInputStream input) {
        this.payload = ReloadResultPayload.read(input);
    }

    @Override
    public void write(ByteBufferOutputStream output) {
        this.payload.write(output);
    }

    public ReloadResultPayload payload() {
        return this.payload;
    }
}
