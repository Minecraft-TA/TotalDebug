package com.github.minecraft_ta.totaldebug.network;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** A piece of a message the server sends Companion through the game client. */
public record ToCompanionPayload(RelayChunk chunk) implements CustomPacketPayload {
    public static final Type<ToCompanionPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(TotalDebug.MOD_ID, "to_companion"));
    public static final StreamCodec<FriendlyByteBuf, ToCompanionPayload> STREAM_CODEC =
            RelayChunk.STREAM_CODEC.map(ToCompanionPayload::new, ToCompanionPayload::chunk);

    @Override
    public Type<ToCompanionPayload> type() {
        return TYPE;
    }
}
