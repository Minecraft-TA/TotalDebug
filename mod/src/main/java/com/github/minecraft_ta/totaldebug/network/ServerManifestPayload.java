package com.github.minecraft_ta.totaldebug.network;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ServerManifestMessage;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ServerManifestPayload(ServerManifestMessage message) implements CustomPacketPayload {
    public static final Type<ServerManifestPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(TotalDebug.MOD_ID, "server_manifest"));
    public static final StreamCodec<FriendlyByteBuf, ServerManifestPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public ServerManifestPayload decode(FriendlyByteBuf buffer) {
            return new ServerManifestPayload(new ServerManifestMessage(buffer.readUtf(64), buffer.readUtf(2048),
                    buffer.readInt(), buffer.readInt(), buffer.readByteArray(ServerManifestMessage.CHUNK_BYTES)));
        }

        @Override
        public void encode(FriendlyByteBuf buffer, ServerManifestPayload payload) {
            var message = payload.message();
            buffer.writeUtf(message.sessionId(), 64);
            buffer.writeUtf(message.detail(), 2048);
            buffer.writeInt(message.offset());
            buffer.writeInt(message.total());
            buffer.writeByteArray(message.bytes());
        }
    };

    @Override
    public Type<ServerManifestPayload> type() { return TYPE; }
}
