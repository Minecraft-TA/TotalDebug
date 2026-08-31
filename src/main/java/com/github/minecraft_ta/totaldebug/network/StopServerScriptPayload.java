package com.github.minecraft_ta.totaldebug.network;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Requests cooperative cancellation of one server-side live script. */
public record StopServerScriptPayload(int scriptId) implements CustomPacketPayload {
    public static final Type<StopServerScriptPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(TotalDebug.MOD_ID, "stop_server_script_v1")
    );
    public static final StreamCodec<FriendlyByteBuf, StopServerScriptPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public StopServerScriptPayload decode(FriendlyByteBuf buffer) {
            return new StopServerScriptPayload(buffer.readInt());
        }

        @Override
        public void encode(FriendlyByteBuf buffer, StopServerScriptPayload payload) {
            buffer.writeInt(payload.scriptId);
        }
    };

    @Override
    public Type<StopServerScriptPayload> type() {
        return TYPE;
    }
}
