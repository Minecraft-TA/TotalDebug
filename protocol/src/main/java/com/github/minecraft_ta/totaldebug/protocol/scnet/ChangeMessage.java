package com.github.minecraft_ta.totaldebug.protocol.scnet;

import com.github.minecraft_ta.totaldebug.protocol.message.ChangePayload;
import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

import java.util.Objects;

/** Companion asks the game to change values it keeps. */
public final class ChangeMessage extends AbstractMessage {
    private ChangePayload payload;

    public ChangeMessage() {
    }

    public ChangeMessage(ChangePayload payload) {
        this.payload = Objects.requireNonNull(payload, "payload");
    }

    @Override
    public void read(ByteBufferInputStream input) {
        this.payload = ChangePayload.read(input);
    }

    @Override
    public void write(ByteBufferOutputStream output) {
        this.payload.write(output);
    }

    public ChangePayload payload() {
        return this.payload;
    }
}
