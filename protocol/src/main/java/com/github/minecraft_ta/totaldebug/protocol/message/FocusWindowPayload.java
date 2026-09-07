package com.github.minecraft_ta.totaldebug.protocol.message;

import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

/** Protocol-11 payload. Field order and encoding are shared by both endpoints. */
public record FocusWindowPayload() {
    public static FocusWindowPayload read(ByteBufferInputStream input) { return new FocusWindowPayload(); }
    public void write(ByteBufferOutputStream output) {

    }
}
