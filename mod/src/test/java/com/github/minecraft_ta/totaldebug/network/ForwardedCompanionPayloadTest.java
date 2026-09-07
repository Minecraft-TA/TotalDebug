package com.github.minecraft_ta.totaldebug.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ForwardedCompanionPayloadTest {
    @Test
    void streamCodecRoundTripsAnExplicitMessageIdAndBody() {
        ForwardedCompanionPayload original = new ForwardedCompanionPayload(
                ResourceLocation.fromNamespaceAndPath("total_debug", "script_status"),
                new byte[]{1, 3, 3, 7}
        );
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

        ForwardedCompanionPayload.STREAM_CODEC.encode(buffer, original);
        ForwardedCompanionPayload decoded = ForwardedCompanionPayload.STREAM_CODEC.decode(buffer);

        assertEquals(original, decoded);
        assertEquals(original.hashCode(), decoded.hashCode());
    }

    @Test
    void bodyIsDefensivelyCopied() {
        byte[] input = {1, 2, 3};
        ForwardedCompanionPayload payload = new ForwardedCompanionPayload(
                ResourceLocation.fromNamespaceAndPath("total_debug", "test"),
                input
        );

        input[0] = 9;
        byte[] returned = payload.body();
        returned[1] = 9;

        assertArrayEquals(new byte[]{1, 2, 3}, payload.body());
    }

    @Test
    void rejectsBodiesOverTheProtocolLimit() {
        byte[] oversized = new byte[ForwardedCompanionPayload.MAX_BODY_BYTES + 1];

        assertThrows(IllegalArgumentException.class, () -> new ForwardedCompanionPayload(
                ResourceLocation.fromNamespaceAndPath("total_debug", "oversized"),
                oversized
        ));
    }
}
