package com.github.minecraft_ta.totaldebug.protocol.scnet;

import com.github.minecraft_ta.totaldebug.protocol.message.PlayingPayload;
import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

/** The game tells what it plays. */
public final class PlayingMessage extends AbstractMessage {
    private PlayingPayload payload;

    public PlayingMessage() {
    }

    public PlayingMessage(PlayingPayload payload) {
        this.payload = payload;
    }

    @Override
    public void read(ByteBufferInputStream input) {
        this.payload = PlayingPayload.read(input);
    }

    @Override
    public void write(ByteBufferOutputStream output) {
        this.payload.write(output);
    }

    public PlayingPayload payload() {
        return this.payload;
    }
}
