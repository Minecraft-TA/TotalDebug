package com.github.minecraft_ta.totaldebug.protocol.scnet;

import com.github.minecraft_ta.totaldebug.protocol.message.PackStackPayload;
import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

import java.util.List;
import java.util.Objects;

/**
 * The world's server names its datapacks, through the relay: when Companion asks ({@link DatapacksRequestMessage}), and
 * again after each time its data loaded. {@code world} is the identity of the world they are of, as
 * {@code PlayingPayload.identity()} gives it, so a report still under way when the game left that world is recognized;
 * empty for the world of a server, which the game client names by the address it joined. {@code refusal}, when not empty,
 * says why the player may not change the world, and the datapacks then go unnamed.
 */
public final class DatapacksMessage extends AbstractMessage {
    public static final int MAX_REFUSAL_LENGTH = 2048;
    private String world;
    private PackStackPayload payload;
    private String refusal;

    public DatapacksMessage() {
    }

    public DatapacksMessage(String world, PackStackPayload payload) {
        this(world, payload, "");
    }

    private DatapacksMessage(String world, PackStackPayload payload, String refusal) {
        this.world = Objects.requireNonNull(world, "world");
        this.payload = Objects.requireNonNull(payload, "payload");
        this.refusal = checked(refusal);
    }

    /** The server of {@code world} does not let the player change it, for {@code refusal}; datapacks of {@code format}. */
    public static DatapacksMessage refused(String world, int format, String refusal) {
        if (refusal.isEmpty()) throw new IllegalArgumentException("A refusal says why");
        return new DatapacksMessage(world, new PackStackPayload(format, List.of()), refusal);
    }

    @Override
    public void read(ByteBufferInputStream input) {
        this.world = input.readString();
        this.payload = PackStackPayload.read(input);
        this.refusal = checked(input.readString());
    }

    @Override
    public void write(ByteBufferOutputStream output) {
        output.writeString(this.world);
        this.payload.write(output);
        output.writeString(this.refusal);
    }

    public String world() {
        return this.world;
    }

    public PackStackPayload payload() {
        return this.payload;
    }

    /** Why the player may not change the world, or empty when they may. */
    public String refusal() {
        return this.refusal;
    }

    private static String checked(String refusal) {
        Objects.requireNonNull(refusal, "refusal");
        if (refusal.length() > MAX_REFUSAL_LENGTH) throw new IllegalArgumentException("Datapack refusal too long");
        return refusal;
    }
}
