package com.github.minecraft_ta.totaldebug.network;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ServerSourceRequestMessage;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ServerSourceRequestPayload(ServerSourceRequestMessage message) implements CustomPacketPayload {
    public static final Type<ServerSourceRequestPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(TotalDebug.MOD_ID, "server_source_request"));
    public static final StreamCodec<FriendlyByteBuf, ServerSourceRequestPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public ServerSourceRequestPayload decode(FriendlyByteBuf buffer) {
            return new ServerSourceRequestPayload(new ServerSourceRequestMessage(
                    buffer.readUtf(64), buffer.readUtf(64), buffer.readInt()));
        }

        @Override
        public void encode(FriendlyByteBuf buffer, ServerSourceRequestPayload payload) {
            buffer.writeUtf(payload.message().sessionId(), 64);
            buffer.writeUtf(payload.message().requestId(), 64);
            buffer.writeInt(payload.message().source());
        }
    };

    @Override
    public Type<ServerSourceRequestPayload> type() { return TYPE; }
}
