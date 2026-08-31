package com.github.minecraft_ta.totaldebug.network;

import com.github.minecraft_ta.totaldebug.script.ScriptExecutionEnvironment;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ServerScriptPayloadTest {
    @Test
    void runPayloadRoundTripsWithinTheServerBoundLimit() {
        RunServerScriptPayload original = new RunServerScriptPayload(
                -1,
                "public class Test extends BaseScript {}",
                ScriptExecutionEnvironment.POST_TICK
        );
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            RunServerScriptPayload.STREAM_CODEC.encode(buffer, original);
            RunServerScriptPayload decoded = RunServerScriptPayload.STREAM_CODEC.decode(buffer);

            assertEquals(original, decoded);
        } finally {
            buffer.release();
        }
    }

    @Test
    void rejectsSourceWhoseUtf8EncodingExceedsTheBound() {
        String oversized = "\u20ac".repeat(RunServerScriptPayload.MAX_SOURCE_BYTES / 3 + 1);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new RunServerScriptPayload(7, oversized, ScriptExecutionEnvironment.THREAD)
        );

        assertEquals(
                "Server script source exceeds 30000 UTF-8 bytes: 30003",
                exception.getMessage()
        );
    }

    @Test
    void stopPayloadRoundTrips() {
        StopServerScriptPayload original = new StopServerScriptPayload(-1);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            StopServerScriptPayload.STREAM_CODEC.encode(buffer, original);

            assertEquals(original, StopServerScriptPayload.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }
}
