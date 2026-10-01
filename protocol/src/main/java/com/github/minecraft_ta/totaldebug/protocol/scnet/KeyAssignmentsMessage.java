package com.github.minecraft_ta.totaldebug.protocol.scnet;

import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

/**
 * The game tells Companion that its key bindings changed and that {@code options.txt} holds them now, as after the
 * player rebound a key in the game's controls screen. Companion reads the file again; the message carries nothing, so
 * the file stays the one place the keys are read from.
 */
public final class KeyAssignmentsMessage extends AbstractMessage {
    @Override
    public void read(ByteBufferInputStream input) {
    }

    @Override
    public void write(ByteBufferOutputStream output) {
    }
}
