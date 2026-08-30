package com.github.minecraft_ta.totaldebug.network;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.script.ScriptStatus;
import com.github.minecraft_ta.totaldebug.script.ScriptStatusType;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

/** Whitelisted wire format for a server script status forwarded through the game client. */
public record ForwardedScriptStatus(int scriptId, ScriptStatus status) {
    public static final ResourceLocation MESSAGE_ID = ResourceLocation.fromNamespaceAndPath(
            TotalDebug.MOD_ID,
            "script_status_v2"
    );
    static final int MAX_MESSAGE_CHARACTERS = 250_000;
    private static final String TRUNCATED_SUFFIX = "\n[TotalDebug truncated the server script output]";

    public ForwardedScriptStatus {
        if (scriptId < 0) {
            throw new IllegalArgumentException("scriptId must not be negative");
        }
        status = Objects.requireNonNull(status, "status");
        String resultJson = status.resultJson();
        if (resultJson != null && resultJson.length() > MAX_MESSAGE_CHARACTERS) {
            throw new IllegalArgumentException(
                    "Structured script result exceeds " + MAX_MESSAGE_CHARACTERS + " characters"
            );
        }
        status = new ScriptStatus(
                status.type(),
                truncate(status.output()),
                resultJson,
                truncate(status.error())
        );
    }

    public ForwardedCompanionPayload toPayload() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeInt(this.scriptId);
            buffer.writeUtf(this.status.type().name(), 32);
            buffer.writeUtf(this.status.output(), MAX_MESSAGE_CHARACTERS);
            buffer.writeBoolean(this.status.resultJson() != null);
            if (this.status.resultJson() != null) {
                buffer.writeUtf(this.status.resultJson(), MAX_MESSAGE_CHARACTERS);
            }
            buffer.writeUtf(this.status.error(), MAX_MESSAGE_CHARACTERS);
            byte[] body = new byte[buffer.readableBytes()];
            buffer.readBytes(body);
            return new ForwardedCompanionPayload(MESSAGE_ID, body);
        } finally {
            buffer.release();
        }
    }

    public static ForwardedScriptStatus fromPayload(ForwardedCompanionPayload payload) {
        Objects.requireNonNull(payload, "payload");
        if (!MESSAGE_ID.equals(payload.messageId())) {
            throw new IllegalArgumentException("Unsupported forwarded companion message: " + payload.messageId());
        }

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.wrappedBuffer(payload.body()));
        try {
            int scriptId = buffer.readInt();
            ScriptStatusType type;
            try {
                type = ScriptStatusType.valueOf(buffer.readUtf(32));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Unknown forwarded script status type", exception);
            }
            String output = buffer.readUtf(MAX_MESSAGE_CHARACTERS);
            String resultJson = buffer.readBoolean() ? buffer.readUtf(MAX_MESSAGE_CHARACTERS) : null;
            String error = buffer.readUtf(MAX_MESSAGE_CHARACTERS);
            if (buffer.isReadable()) {
                throw new IllegalArgumentException("Forwarded script status contains trailing bytes");
            }
            return new ForwardedScriptStatus(scriptId, new ScriptStatus(type, output, resultJson, error));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Malformed forwarded script status", exception);
        } finally {
            buffer.release();
        }
    }

    private static String truncate(String message) {
        if (message.length() <= MAX_MESSAGE_CHARACTERS) {
            return message;
        }
        int retainedCharacters = MAX_MESSAGE_CHARACTERS - TRUNCATED_SUFFIX.length();
        return message.substring(0, retainedCharacters) + TRUNCATED_SUFFIX;
    }
}
