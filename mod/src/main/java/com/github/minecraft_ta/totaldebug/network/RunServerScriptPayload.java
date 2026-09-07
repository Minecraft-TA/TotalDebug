package com.github.minecraft_ta.totaldebug.network;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.protocol.execution.ScriptBytecode;
import com.github.minecraft_ta.totaldebug.protocol.execution.ScriptExecutionEnvironment;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Requests one bounded live-script run on the logical server. */
public record RunServerScriptPayload(
        int scriptId,
        ScriptBytecode bytecode,
        ScriptExecutionEnvironment environment
) implements CustomPacketPayload {
    /** Leaves room below Minecraft's 32 KiB server-bound custom-payload limit. */
    public static final int MAX_BYTECODE_BYTES = 30_000;
    public static final Type<RunServerScriptPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(TotalDebug.MOD_ID, "run_server_script_v2")
    );
    public static final StreamCodec<FriendlyByteBuf, RunServerScriptPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public RunServerScriptPayload decode(FriendlyByteBuf buffer) {
            int scriptId = buffer.readInt();
            ScriptBytecode bytecode = decodeBytecode(buffer.readByteArray(MAX_BYTECODE_BYTES));
            ScriptExecutionEnvironment environment = ScriptExecutionEnvironment.fromWireName(buffer.readUtf(32));
            return new RunServerScriptPayload(scriptId, bytecode, environment);
        }

        @Override
        public void encode(FriendlyByteBuf buffer, RunServerScriptPayload payload) {
            buffer.writeInt(payload.scriptId);
            buffer.writeByteArray(encodeBytecode(payload.bytecode));
            buffer.writeUtf(payload.environment.name(), 32);
        }
    };

    public RunServerScriptPayload {
        Objects.requireNonNull(bytecode, "bytecode");
        Objects.requireNonNull(environment, "environment");
        if (encodeBytecode(bytecode).length > MAX_BYTECODE_BYTES) {
            throw new IllegalArgumentException("Compressed server script exceeds " + MAX_BYTECODE_BYTES + " bytes");
        }
    }

    private static byte[] encodeBytecode(ScriptBytecode bytecode) {
        var output = new ByteBufferOutputStream();
        bytecode.write(output);
        ByteBuffer buffer = output.getBuffer().duplicate();
        buffer.flip();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        var compressed = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(compressed)) {
            gzip.write(bytes);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Unable to compress script bytecode", exception);
        }
        return compressed.toByteArray();
    }

    private static ScriptBytecode decodeBytecode(byte[] compressed) {
        try (var gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            byte[] bytes = gzip.readNBytes(ScriptBytecode.MAX_BYTES + 1);
            if (bytes.length > ScriptBytecode.MAX_BYTES) throw new IOException("Script bytecode exceeds the decoded size limit");
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            ScriptBytecode bytecode = ScriptBytecode.read(new ByteBufferInputStream(buffer));
            if (buffer.hasRemaining()) throw new IOException("Trailing script bytecode data");
            return bytecode;
        } catch (IOException exception) {
            throw new IllegalArgumentException("Invalid compressed script bytecode", exception);
        }
    }

    @Override
    public Type<RunServerScriptPayload> type() {
        return TYPE;
    }
}
