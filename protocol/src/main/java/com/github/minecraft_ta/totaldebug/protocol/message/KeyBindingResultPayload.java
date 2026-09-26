package com.github.minecraft_ta.totaldebug.protocol.message;

import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

import java.util.Objects;

/**
 * Protocol-23 payload answering a {@link SetKeyBindingPayload}: the binding's key and modifier before and after, or
 * why it was not changed in {@code error}, which is empty on success.
 */
public record KeyBindingResultPayload(int requestId, String name, String previousKey, String previousModifier,
                                      String key, String modifier, String error) {
    public KeyBindingResultPayload {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(previousKey, "previousKey");
        Objects.requireNonNull(previousModifier, "previousModifier");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(modifier, "modifier");
        Objects.requireNonNull(error, "error");
    }

    public static KeyBindingResultPayload read(ByteBufferInputStream input) {
        return new KeyBindingResultPayload(input.readInt(), input.readString(), input.readString(), input.readString(),
                input.readString(), input.readString(), input.readString());
    }

    public void write(ByteBufferOutputStream output) {
        output.writeInt(this.requestId);
        output.writeString(this.name);
        output.writeString(this.previousKey);
        output.writeString(this.previousModifier);
        output.writeString(this.key);
        output.writeString(this.modifier);
        output.writeString(this.error);
    }
}
