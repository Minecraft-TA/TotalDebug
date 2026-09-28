package com.github.minecraft_ta.totaldebug.protocol.scnet;

import com.github.minecraft_ta.totaldebug.protocol.relay.RelayedMessage;
import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

/** The game client hands Companion a message the game's server sent, unread. */
public final class FromServerMessage extends AbstractMessage {
    private RelayedMessage payload;

    public FromServerMessage() {
    }

    public FromServerMessage(RelayedMessage payload) {
        this.payload = payload;
    }

    @Override
    public void read(ByteBufferInputStream input) {
        this.payload = RelayedMessage.read(input);
    }

    @Override
    public void write(ByteBufferOutputStream output) {
        this.payload.write(output);
    }

    public RelayedMessage payload() {
        return this.payload;
    }
}
