package com.github.minecraft_ta.totaldebug.protocol.scnet;

import com.github.minecraft_ta.totaldebug.protocol.message.PackStackPayload;
import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

import java.util.Objects;

/**
 * The world's server names its datapacks, through the relay: when Companion asks ({@link DatapacksRequestMessage}), and
 * again after each time its data loaded. {@code world} is the identity of the world they are of, as
 * {@code PlayingPayload.identity()} gives it, so a report still under way when the game left that world is recognized.
 */
public final class DatapacksMessage extends AbstractMessage {
    private String world;
    private PackStackPayload payload;

    public DatapacksMessage() {
    }

    public DatapacksMessage(String world, PackStackPayload payload) {
        this.world = Objects.requireNonNull(world, "world");
        this.payload = Objects.requireNonNull(payload, "payload");
    }

    @Override
    public void read(ByteBufferInputStream input) {
        this.world = input.readString();
        this.payload = PackStackPayload.read(input);
    }

    @Override
    public void write(ByteBufferOutputStream output) {
        output.writeString(this.world);
        this.payload.write(output);
    }

    public String world() {
        return this.world;
    }

    public PackStackPayload payload() {
        return this.payload;
    }
}
