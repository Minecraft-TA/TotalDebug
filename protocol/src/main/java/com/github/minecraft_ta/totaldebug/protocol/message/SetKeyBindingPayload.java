package com.github.minecraft_ta.totaldebug.protocol.message;

import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

import java.util.Objects;

/**
 * Protocol-23 payload asking the game to put a key binding on a key. {@code name} is the binding's name in
 * {@code options.txt}, {@code key} a Minecraft key name such as {@code key.keyboard.g} and {@code modifier} one of
 * {@code NONE}, {@code SHIFT}, {@code CONTROL} and {@code ALT}.
 */
public record SetKeyBindingPayload(int requestId, String name, String key, String modifier) {
    public SetKeyBindingPayload {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(modifier, "modifier");
    }

    public static SetKeyBindingPayload read(ByteBufferInputStream input) {
        return new SetKeyBindingPayload(input.readInt(), input.readString(), input.readString(), input.readString());
    }

    public void write(ByteBufferOutputStream output) {
        output.writeInt(this.requestId);
        output.writeString(this.name);
        output.writeString(this.key);
        output.writeString(this.modifier);
    }
}
