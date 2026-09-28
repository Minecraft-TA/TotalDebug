package com.github.minecraft_ta.totaldebug.protocol.scnet;

import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

/**
 * Companion asks the game's server, through the relay, whether it runs scripts for this player. {@code request}
 * identifies the question, which the answer repeats.
 */
public final class ServerScriptsRequestMessage extends AbstractMessage {
    private int request;

    public ServerScriptsRequestMessage() {
    }

    public ServerScriptsRequestMessage(int request) {
        this.request = request;
    }

    public int request() {
        return this.request;
    }

    @Override
    public void read(ByteBufferInputStream input) {
        this.request = input.readInt();
    }

    @Override
    public void write(ByteBufferOutputStream output) {
        output.writeInt(this.request);
    }
}
