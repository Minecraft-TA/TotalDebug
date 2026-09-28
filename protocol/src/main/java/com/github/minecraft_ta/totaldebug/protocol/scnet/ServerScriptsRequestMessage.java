package com.github.minecraft_ta.totaldebug.protocol.scnet;

import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

/** Companion asks the game's server, through the relay, whether it runs scripts for this player. */
public final class ServerScriptsRequestMessage extends AbstractMessage {
    @Override
    public void read(ByteBufferInputStream input) {
    }

    @Override
    public void write(ByteBufferOutputStream output) {
    }
}
