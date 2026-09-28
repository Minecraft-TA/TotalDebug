package com.github.minecraft_ta.totaldebug.protocol.message;

import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Protocol-29 payload asking the game to enable exactly {@code enabled}, lowest first, among the resource packs or the
 * open singleplayer world's datapacks, and reload what that needs. The game answers with a {@link ReloadResultPayload}
 * of the same request id.
 */
public record SetPacksPayload(int requestId, Side side, List<String> enabled) {
    /** Which packs. */
    public enum Side {
        RESOURCES,
        DATA
    }

    public SetPacksPayload {
        Objects.requireNonNull(side, "side");
        enabled = List.copyOf(enabled);
        if (enabled.size() > PackStackPayload.MAX_PACKS) throw new IllegalArgumentException("Too many packs");
    }

    public static SetPacksPayload read(ByteBufferInputStream input) {
        int requestId = input.readInt();
        int side = input.readInt();
        if (side < 0 || side >= Side.values().length) throw new IllegalArgumentException("Invalid side: " + side);
        int count = input.readInt();
        if (count < 0 || count > PackStackPayload.MAX_PACKS) throw new IllegalArgumentException("Invalid pack count: " + count);
        List<String> enabled = new ArrayList<>(count);
        for (int index = 0; index < count; index++) enabled.add(input.readString());
        return new SetPacksPayload(requestId, Side.values()[side], enabled);
    }

    public void write(ByteBufferOutputStream output) {
        output.writeInt(this.requestId);
        output.writeInt(this.side.ordinal());
        output.writeInt(this.enabled.size());
        for (String id : this.enabled) output.writeString(id);
    }
}
