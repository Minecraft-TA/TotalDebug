package com.github.minecraft_ta.totaldebug.network;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Arrays;
import java.util.Objects;

/**
 * Carries an explicitly identified companion message from a server to a client.
 *
 * <p>The legacy packet serialized a Java class name and reconstructed it with
 * reflection. The resource id is a stable protocol identifier instead; F4 owns
 * the registry that maps these ids to concrete companion message codecs.</p>
 */
public record ForwardedCompanionPayload(ResourceLocation messageId, byte[] body) implements CustomPacketPayload {
    public static final int MAX_BODY_BYTES = 1_048_576;
    public static final Type<ForwardedCompanionPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(TotalDebug.MOD_ID, "forwarded_companion_message")
    );
    public static final StreamCodec<FriendlyByteBuf, ForwardedCompanionPayload> STREAM_CODEC = StreamCodec.composite(
            ResourceLocation.STREAM_CODEC,
            ForwardedCompanionPayload::messageId,
            ByteBufCodecs.byteArray(MAX_BODY_BYTES),
            payload -> payload.body,
            ForwardedCompanionPayload::new
    );

    public ForwardedCompanionPayload {
        Objects.requireNonNull(messageId, "messageId");
        body = Arrays.copyOf(Objects.requireNonNull(body, "body"), body.length);
        if (body.length > MAX_BODY_BYTES) {
            throw new IllegalArgumentException(
                    "Forwarded companion payload exceeds " + MAX_BODY_BYTES + " bytes: " + body.length
            );
        }
    }

    @Override
    public byte[] body() {
        return Arrays.copyOf(this.body, this.body.length);
    }

    @Override
    public Type<ForwardedCompanionPayload> type() {
        return TYPE;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof ForwardedCompanionPayload that
                && this.messageId.equals(that.messageId)
                && Arrays.equals(this.body, that.body);
    }

    @Override
    public int hashCode() {
        return 31 * this.messageId.hashCode() + Arrays.hashCode(this.body);
    }
}
