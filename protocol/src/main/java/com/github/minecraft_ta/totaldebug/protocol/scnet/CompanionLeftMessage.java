package com.github.minecraft_ta.totaldebug.protocol.scnet;

import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

/**
 * The game client tells its server, through the relay, that Companion's connection closed: the manifest session
 * Companion asked for ends, with the runs in it. The one relayed message the client writes itself.
 */
public final class CompanionLeftMessage extends AbstractMessage {
    @Override
    public void read(ByteBufferInputStream input) {
    }

    @Override
    public void write(ByteBufferOutputStream output) {
    }
}
