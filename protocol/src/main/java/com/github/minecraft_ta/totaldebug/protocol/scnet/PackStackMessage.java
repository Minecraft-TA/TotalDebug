package com.github.minecraft_ta.totaldebug.protocol.scnet;

import com.github.minecraft_ta.totaldebug.protocol.message.ClientPacksPayload;
import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

/** The game names its enabled packs. */
public final class PackStackMessage extends AbstractMessage {
    private ClientPacksPayload payload;

    public PackStackMessage() {
    }

    public PackStackMessage(ClientPacksPayload payload) {
        this.payload = payload;
    }

    @Override
    public void read(ByteBufferInputStream input) {
        this.payload = ClientPacksPayload.read(input);
    }

    @Override
    public void write(ByteBufferOutputStream output) {
        this.payload.write(output);
    }

    public ClientPacksPayload payload() {
        return this.payload;
    }
}
