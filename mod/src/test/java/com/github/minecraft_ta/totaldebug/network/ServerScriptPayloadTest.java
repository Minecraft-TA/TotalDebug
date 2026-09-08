package com.github.minecraft_ta.totaldebug.network;

import com.github.minecraft_ta.totaldebug.protocol.execution.ScriptExecutionEnvironment;
import com.github.minecraft_ta.totaldebug.protocol.execution.ScriptBytecode;
import java.util.Map;
import java.util.Random;
import java.io.ByteArrayOutputStream;
import java.util.zip.GZIPOutputStream;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ServerScriptPayloadTest {
    @Test
    void compressedBytecodeCanExceedTheUncompressedPacketLimit() {
        byte[] bytes = new byte[50_000];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (i % 251);
        var original = new RunServerScriptPayload(8, new ScriptBytecode("Large", Map.of("Large", bytes)),
                ScriptExecutionEnvironment.THREAD);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            RunServerScriptPayload.STREAM_CODEC.encode(buffer, original);
            var decoded = RunServerScriptPayload.STREAM_CODEC.decode(buffer);
            assertArrayEquals(bytes, decoded.bytecode().classes().get("Large"));
        } finally {
            buffer.release();
        }
    }

    @Test
    void rejectsCompressedPayloadThatExpandsBeyondTheClassBundleLimit() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(bytes)) {
            gzip.write(new byte[ScriptBytecode.MAX_BYTES + 1]);
        }
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeInt(9);
            buffer.writeByteArray(bytes.toByteArray());
            buffer.writeUtf("THREAD");
            assertThrows(IllegalArgumentException.class, () -> RunServerScriptPayload.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void runPayloadRoundTripsWithinTheServerBoundLimit() {
        RunServerScriptPayload original = new RunServerScriptPayload(
                -1,
                new ScriptBytecode("Test", Map.of("Test", new byte[]{1, 2, 3})),
                ScriptExecutionEnvironment.POST_TICK
        );
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            RunServerScriptPayload.STREAM_CODEC.encode(buffer, original);
            RunServerScriptPayload decoded = RunServerScriptPayload.STREAM_CODEC.decode(buffer);

            assertEquals(original.scriptId(), decoded.scriptId());
            assertEquals(original.environment(), decoded.environment());
            assertEquals(original.bytecode().primaryClass(), decoded.bytecode().primaryClass());
            assertArrayEquals(original.bytecode().classes().get("Test"), decoded.bytecode().classes().get("Test"));
        } finally {
            buffer.release();
        }
    }

    @Test
    void rejectsBytecodeExceedingTheServerBoundLimit() {
        byte[] bytes = new byte[40_000];
        new Random(42).nextBytes(bytes);
        ScriptBytecode oversized = new ScriptBytecode("Test", Map.of("Test", bytes));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new RunServerScriptPayload(7, oversized, ScriptExecutionEnvironment.THREAD)
        );

        assertEquals(
                "Compressed server script exceeds 30000 bytes",
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
