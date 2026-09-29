package com.github.minecraft_ta.totaldebug.protocol.scnet;

import com.github.minecraft_ta.totaldebug.protocol.message.PreparedFilePayload;
import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

import java.util.Objects;

/** The game tells the state of a file it prepares for Companion. */
public final class PreparedFileMessage extends AbstractMessage {
    private PreparedFilePayload payload;

    public PreparedFileMessage() {
    }

    public PreparedFileMessage(PreparedFilePayload payload) {
        this.payload = Objects.requireNonNull(payload, "payload");
    }

    @Override
    public void read(ByteBufferInputStream input) {
        this.payload = PreparedFilePayload.read(input);
    }

    @Override
    public void write(ByteBufferOutputStream output) {
        this.payload.write(output);
    }

    public PreparedFilePayload payload() {
        return this.payload;
    }
}
