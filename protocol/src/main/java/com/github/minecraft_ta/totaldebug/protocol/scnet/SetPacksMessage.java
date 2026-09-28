package com.github.minecraft_ta.totaldebug.protocol.scnet;

import com.github.minecraft_ta.totaldebug.protocol.message.SetPacksPayload;
import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

/** Companion asks the game to enable and order its resource packs or the open world's datapacks. */
public final class SetPacksMessage extends AbstractMessage {
    private SetPacksPayload payload;

    public SetPacksMessage() {
    }

    public SetPacksMessage(SetPacksPayload payload) {
        this.payload = payload;
    }

    @Override
    public void read(ByteBufferInputStream input) {
        this.payload = SetPacksPayload.read(input);
    }

    @Override
    public void write(ByteBufferOutputStream output) {
        this.payload.write(output);
    }

    public SetPacksPayload payload() {
        return this.payload;
    }
}
