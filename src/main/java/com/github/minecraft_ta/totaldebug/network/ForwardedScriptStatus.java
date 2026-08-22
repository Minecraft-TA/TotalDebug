package com.github.minecraft_ta.totaldebug.network;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.script.ScriptStatusType;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

/** Whitelisted wire format for a server script status forwarded through the game client. */
public record ForwardedScriptStatus(int scriptId, ScriptStatusType type, String message) {
    public static final ResourceLocation MESSAGE_ID = ResourceLocation.fromNamespaceAndPath(
            TotalDebug.MOD_ID,
            "script_status_v1"
    );
    static final int MAX_MESSAGE_CHARACTERS = 250_000;
    private static final String TRUNCATED_SUFFIX = "\n[TotalDebug truncated the server script output]";

    public ForwardedScriptStatus {
        if (scriptId < 0) {
            throw new IllegalArgumentException("scriptId must not be negative");
        }
        type = Objects.requireNonNull(type, "type");
        message = truncate(Objects.requireNonNullElse(message, ""));
    }

    public ForwardedCompanionPayload toPayload() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeInt(this.scriptId);
            buffer.writeUtf(this.type.name(), 32);
            buffer.writeUtf(this.message, MAX_MESSAGE_CHARACTERS);
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
            String message = buffer.readUtf(MAX_MESSAGE_CHARACTERS);
            if (buffer.isReadable()) {
                throw new IllegalArgumentException("Forwarded script status contains trailing bytes");
            }
            return new ForwardedScriptStatus(scriptId, type, message);
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
