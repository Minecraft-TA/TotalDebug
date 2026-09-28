package com.github.minecraft_ta.totaldebug.protocol.message;

import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Protocol-30 payload asking the game to enable exactly {@code enabled}, lowest first, among the resource packs or the
 * datapacks of the singleplayer world {@code world}, and reload what that needs. {@code world} is the world's folder as
 * {@code PLAYING} names it, empty for resource packs; the game refuses a selection for a world it no longer plays. The
 * game answers with a {@link ReloadResultPayload} of the same request id.
 */
public record SetPacksPayload(int requestId, Side side, String world, List<String> enabled) {
    /** Which packs. */
    public enum Side {
        RESOURCES,
        DATA
    }

    public SetPacksPayload {
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(world, "world");
        if (world.isEmpty() != (side == Side.RESOURCES)) {
            throw new IllegalArgumentException("A datapack selection names its world, and a resource pack selection none");
        }
        enabled = List.copyOf(enabled);
        if (enabled.size() > PackStackPayload.MAX_PACKS) throw new IllegalArgumentException("Too many packs");
    }

    public static SetPacksPayload read(ByteBufferInputStream input) {
        int requestId = input.readInt();
        int side = input.readInt();
        if (side < 0 || side >= Side.values().length) throw new IllegalArgumentException("Invalid side: " + side);
        String world = input.readString();
        int count = input.readInt();
        if (count < 0 || count > PackStackPayload.MAX_PACKS) throw new IllegalArgumentException("Invalid pack count: " + count);
        List<String> enabled = new ArrayList<>(count);
        for (int index = 0; index < count; index++) enabled.add(input.readString());
        return new SetPacksPayload(requestId, Side.values()[side], world, enabled);
    }

    public void write(ByteBufferOutputStream output) {
        output.writeInt(this.requestId);
        output.writeInt(this.side.ordinal());
        output.writeString(this.world);
        output.writeInt(this.enabled.size());
        for (String id : this.enabled) output.writeString(id);
    }
}
