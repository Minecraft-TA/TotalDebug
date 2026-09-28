package com.github.minecraft_ta.totaldebug.protocol.message;

import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

import java.util.Objects;

/**
 * Protocol-30 payload asking the game to set the game rule {@code name} of the singleplayer world it has open to
 * {@code value}, as {@code /gamerule} does. The game answers with a {@link ReloadResultPayload} of the same request id.
 */
public record SetGameRulePayload(int requestId, String name, String value) {
    public SetGameRulePayload {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
    }

    public static SetGameRulePayload read(ByteBufferInputStream input) {
        return new SetGameRulePayload(input.readInt(), input.readString(), input.readString());
    }

    public void write(ByteBufferOutputStream output) {
        output.writeInt(this.requestId);
        output.writeString(this.name);
        output.writeString(this.value);
    }
}
