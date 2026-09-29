package com.github.minecraft_ta.totaldebug.protocol.scnet;

import com.github.minecraft_ta.totaldebug.protocol.message.ChangeResultPayload;
import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

import java.util.Objects;

/** The game answers a change. */
public final class ChangeResultMessage extends AbstractMessage {
    private ChangeResultPayload payload;

    public ChangeResultMessage() {
    }

    public ChangeResultMessage(ChangeResultPayload payload) {
        this.payload = Objects.requireNonNull(payload, "payload");
    }

    @Override
    public void read(ByteBufferInputStream input) {
        this.payload = ChangeResultPayload.read(input);
    }

    @Override
    public void write(ByteBufferOutputStream output) {
        this.payload.write(output);
    }

    public ChangeResultPayload payload() {
        return this.payload;
    }
}
