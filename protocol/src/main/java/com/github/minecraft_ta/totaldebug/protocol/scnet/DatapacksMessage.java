package com.github.minecraft_ta.totaldebug.protocol.scnet;

import com.github.minecraft_ta.totaldebug.protocol.message.PackStackPayload;
import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

import java.util.Objects;

/**
 * The world's server names its datapacks, through the relay: when Companion asks ({@link DatapacksRequestMessage}), and
 * again after each time its data loaded.
 */
public final class DatapacksMessage extends AbstractMessage {
    private PackStackPayload payload;

    public DatapacksMessage() {
    }

    public DatapacksMessage(PackStackPayload payload) {
        this.payload = Objects.requireNonNull(payload, "payload");
    }

    @Override
    public void read(ByteBufferInputStream input) {
        this.payload = PackStackPayload.read(input);
    }

    @Override
    public void write(ByteBufferOutputStream output) {
        this.payload.write(output);
    }

    public PackStackPayload payload() {
        return this.payload;
    }
}
