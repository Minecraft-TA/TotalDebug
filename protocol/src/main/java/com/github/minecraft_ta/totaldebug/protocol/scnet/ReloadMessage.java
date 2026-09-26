package com.github.minecraft_ta.totaldebug.protocol.scnet;

import com.github.minecraft_ta.totaldebug.protocol.message.ReloadPayload;
import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

/** Companion asks the game to reload what edited resources need. */
public final class ReloadMessage extends AbstractMessage {
    private ReloadPayload payload;

    public ReloadMessage() {
    }

    public ReloadMessage(ReloadPayload payload) {
        this.payload = payload;
    }

    @Override
    public void read(ByteBufferInputStream input) {
        this.payload = ReloadPayload.read(input);
    }

    @Override
    public void write(ByteBufferOutputStream output) {
        this.payload.write(output);
    }

    public ReloadPayload payload() {
        return this.payload;
    }
}
