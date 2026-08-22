package com.github.minecraft_ta.totaldebug.network;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.script.ScriptExecutionEnvironment;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Requests one bounded live-script run on the logical server. */
public record RunServerScriptPayload(
        int scriptId,
        String sourceCode,
        ScriptExecutionEnvironment environment
) implements CustomPacketPayload {
    /** Leaves room below Minecraft's 32 KiB server-bound custom-payload limit. */
    public static final int MAX_SOURCE_BYTES = 30_000;
    public static final Type<RunServerScriptPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(TotalDebug.MOD_ID, "run_server_script_v1")
    );
    public static final StreamCodec<FriendlyByteBuf, RunServerScriptPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public RunServerScriptPayload decode(FriendlyByteBuf buffer) {
            int scriptId = buffer.readInt();
            String sourceCode = new String(buffer.readByteArray(MAX_SOURCE_BYTES), StandardCharsets.UTF_8);
            ScriptExecutionEnvironment environment = ScriptExecutionEnvironment.fromWireName(buffer.readUtf(32));
            return new RunServerScriptPayload(scriptId, sourceCode, environment);
        }

        @Override
        public void encode(FriendlyByteBuf buffer, RunServerScriptPayload payload) {
            buffer.writeInt(payload.scriptId);
            buffer.writeByteArray(payload.sourceBytes());
            buffer.writeUtf(payload.environment.name(), 32);
        }
    };

    public RunServerScriptPayload {
        if (scriptId < 0) {
            throw new IllegalArgumentException("scriptId must not be negative");
        }
        sourceCode = Objects.requireNonNull(sourceCode, "sourceCode");
        environment = Objects.requireNonNull(environment, "environment");
        int sourceBytes = sourceCode.getBytes(StandardCharsets.UTF_8).length;
        if (sourceBytes > MAX_SOURCE_BYTES) {
            throw new IllegalArgumentException(
                    "Server script source exceeds " + MAX_SOURCE_BYTES + " UTF-8 bytes: " + sourceBytes
            );
        }
    }

    private byte[] sourceBytes() {
        return this.sourceCode.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public Type<RunServerScriptPayload> type() {
        return TYPE;
    }
}
