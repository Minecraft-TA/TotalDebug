package com.github.minecraft_ta.totaldebug.protocol.scnet;

import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

/** Companion asks the world's server, through the relay, to name its datapacks ({@link DatapacksMessage}). */
public final class DatapacksRequestMessage extends AbstractMessage {
    @Override
    public void read(ByteBufferInputStream input) {
    }

    @Override
    public void write(ByteBufferOutputStream output) {
    }
}
