package com.github.minecraft_ta.totaldebug.protocol.message;

import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

import java.util.Objects;

/**
 * Protocol-38 payload of the game client's packs ({@code PACK_STACK}): its resource packs, and the datapack format of its
 * Minecraft version, for a datapack Companion creates while no world's server names its datapacks.
 */
public record ClientPacksPayload(PackStackPayload resourcePacks, int dataFormat) {
    public ClientPacksPayload {
        Objects.requireNonNull(resourcePacks, "resourcePacks");
    }

    public static ClientPacksPayload read(ByteBufferInputStream input) {
        return new ClientPacksPayload(PackStackPayload.read(input), input.readInt());
    }

    public void write(ByteBufferOutputStream output) {
        this.resourcePacks.write(output);
        output.writeInt(this.dataFormat);
    }
}
