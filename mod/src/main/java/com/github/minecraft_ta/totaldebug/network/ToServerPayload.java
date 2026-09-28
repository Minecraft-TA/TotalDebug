package com.github.minecraft_ta.totaldebug.network;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** A piece of a Companion message the game client carries to the server. */
public record ToServerPayload(RelayChunk chunk) implements CustomPacketPayload {
    public static final Type<ToServerPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(TotalDebug.MOD_ID, "to_server"));
    public static final StreamCodec<FriendlyByteBuf, ToServerPayload> STREAM_CODEC =
            RelayChunk.STREAM_CODEC.map(ToServerPayload::new, ToServerPayload::chunk);

    @Override
    public Type<ToServerPayload> type() {
        return TYPE;
    }
}
