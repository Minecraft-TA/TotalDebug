package com.github.minecraft_ta.totaldebug.protocol.scnet;

import com.github.minecraft_ta.totaldebug.protocol.message.SetOverlayPayload;
import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

/** Companion puts a resource into the game's in-memory pack or removes it. */
public final class SetOverlayMessage extends AbstractMessage {
    private SetOverlayPayload payload;

    public SetOverlayMessage() {
    }

    public SetOverlayMessage(SetOverlayPayload payload) {
        this.payload = payload;
    }

    @Override
    public void read(ByteBufferInputStream input) {
        this.payload = SetOverlayPayload.read(input);
    }

    @Override
    public void write(ByteBufferOutputStream output) {
        this.payload.write(output);
    }

    public SetOverlayPayload payload() {
        return this.payload;
    }
}
