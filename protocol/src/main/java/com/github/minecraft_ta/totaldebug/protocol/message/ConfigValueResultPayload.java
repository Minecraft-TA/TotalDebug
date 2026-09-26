package com.github.minecraft_ta.totaldebug.protocol.message;

import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

import java.util.Objects;

/**
 * Protocol-24 payload answering a {@link SetConfigValuePayload}: the value the game used before and uses now, as the
 * game prints them, or why it kept its value in {@code error}, which is empty on success.
 */
public record ConfigValueResultPayload(int requestId, String previous, String current, String error) {
    public ConfigValueResultPayload {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(error, "error");
    }

    public static ConfigValueResultPayload read(ByteBufferInputStream input) {
        return new ConfigValueResultPayload(input.readInt(), input.readString(), input.readString(), input.readString());
    }

    public void write(ByteBufferOutputStream output) {
        output.writeInt(this.requestId);
        output.writeString(this.previous);
        output.writeString(this.current);
        output.writeString(this.error);
    }
}
