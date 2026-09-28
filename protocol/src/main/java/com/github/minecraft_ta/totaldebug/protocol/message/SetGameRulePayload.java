package com.github.minecraft_ta.totaldebug.protocol.message;

import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

import java.util.Objects;

/**
 * Protocol-30 payload asking the game to set the game rule {@code name} of the singleplayer world it has open to
 * {@code value}, as {@code /gamerule} does. {@code world} is the folder name of the world meant, and {@code expected}
 * the value the rule must still have; the game refuses the request for another world or a rule changed since. The game
 * answers with a {@link ReloadResultPayload} of the same request id.
 */
public record SetGameRulePayload(int requestId, String world, String name, String expected, String value) {
    public SetGameRulePayload {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(value, "value");
    }

    public static SetGameRulePayload read(ByteBufferInputStream input) {
        return new SetGameRulePayload(input.readInt(), input.readString(), input.readString(), input.readString(), input.readString());
    }

    public void write(ByteBufferOutputStream output) {
        output.writeInt(this.requestId);
        output.writeString(this.world);
        output.writeString(this.name);
        output.writeString(this.expected);
        output.writeString(this.value);
    }
}
