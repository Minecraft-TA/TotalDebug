package com.github.minecraft_ta.totaldebug.protocol.scnet;

import com.github.minecraft_ta.totaldebug.protocol.message.SetConfigValuePayload;
import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

/** Companion sets a configuration value in the running game's memory. */
public final class SetConfigValueMessage extends AbstractMessage {
    private SetConfigValuePayload payload;

    public SetConfigValueMessage() {
    }

    public SetConfigValueMessage(SetConfigValuePayload payload) {
        this.payload = payload;
    }

    @Override
    public void read(ByteBufferInputStream input) {
        this.payload = SetConfigValuePayload.read(input);
    }

    @Override
    public void write(ByteBufferOutputStream output) {
        this.payload.write(output);
    }

    public SetConfigValuePayload payload() {
        return this.payload;
    }
}
