package com.github.minecraft_ta.totaldebug.protocol.message;

import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

import java.util.Objects;

/**
 * Protocol-24 payload setting a configuration value in the running game's memory, without writing its file.
 * {@code fileName} is the configuration's file name as NeoForge tracks it, such as {@code testmod-common.toml},
 * {@code setting} the dotted path of the value and {@code literal} the new value written as in TOML.
 */
public record SetConfigValuePayload(int requestId, String fileName, String setting, String literal) {
    public SetConfigValuePayload {
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(setting, "setting");
        Objects.requireNonNull(literal, "literal");
    }

    public static SetConfigValuePayload read(ByteBufferInputStream input) {
        return new SetConfigValuePayload(input.readInt(), input.readString(), input.readString(), input.readString());
    }

    public void write(ByteBufferOutputStream output) {
        output.writeInt(this.requestId);
        output.writeString(this.fileName);
        output.writeString(this.setting);
        output.writeString(this.literal);
    }
}
