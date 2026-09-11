package com.github.minecraft_ta.totaldebug.network;

import com.github.minecraft_ta.totaldebug.protocol.execution.ScriptExecutionEnvironment;
import com.github.minecraft_ta.totaldebug.protocol.execution.ScriptBytecode;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ServerManifestMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ServerSourceRequestMessage;
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
                ScriptExecutionEnvironment.THREAD, "server-session");
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
                ScriptExecutionEnvironment.POST_TICK, "server-session"
        );
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            RunServerScriptPayload.STREAM_CODEC.encode(buffer, original);
            RunServerScriptPayload decoded = RunServerScriptPayload.STREAM_CODEC.decode(buffer);

            assertEquals(original.scriptId(), decoded.scriptId());
            assertEquals(original.environment(), decoded.environment());
            assertEquals("server-session", decoded.serverSessionId());
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
                () -> new RunServerScriptPayload(7, oversized, ScriptExecutionEnvironment.THREAD, "server-session")
        );

        assertEquals(
                "Compressed server script exceeds 30000 bytes",
                exception.getMessage()
        );
    }

    @Test
    void serverRunsCannotOmitTheHandshakeIdentity() {
        var bytecode = new ScriptBytecode("Test", Map.of("Test", new byte[]{1}));
        assertThrows(IllegalArgumentException.class,
                () -> new RunServerScriptPayload(7, bytecode, ScriptExecutionEnvironment.THREAD, ""));
    }

    @Test
    void manifestChunksRoundTripThroughTheGameTransport() {
        byte[] bytes = new byte[ServerManifestMessage.CHUNK_BYTES + 17];
        new Random(9).nextBytes(bytes);
        var assembler = new ServerManifestMessage.Assembler();
        byte[] result = null;
        for (var message : ServerManifestMessage.split("session", "request", 12, bytes)) {
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                ServerManifestPayload.STREAM_CODEC.encode(buffer, new ServerManifestPayload(message));
                var read = ServerManifestPayload.STREAM_CODEC.decode(buffer);
                assertEquals("request", read.message().requestId());
                assertEquals(12, read.message().source());
                result = assembler.accept(read.message());
                assertEquals(0, buffer.readableBytes());
            } finally {
                buffer.release();
            }
        }
        assertArrayEquals(bytes, result);
    }

    @Test
    void sourceRequestRoundTripsThroughGameTransport() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            ServerSourceRequestPayload.STREAM_CODEC.encode(buffer,
                    new ServerSourceRequestPayload(new ServerSourceRequestMessage("session", "request", 17)));
            var read = ServerSourceRequestPayload.STREAM_CODEC.decode(buffer).message();
            assertEquals("session", read.sessionId());
            assertEquals("request", read.requestId());
            assertEquals(17, read.source());
            assertEquals(0, buffer.readableBytes());
        } finally { buffer.release(); }
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
