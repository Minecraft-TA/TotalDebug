package com.github.minecraft_ta.totaldebug.protocol.message;

import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

/** Protocol-11 payload. Field order and encoding are shared by both endpoints. */
public record ReadyPayload() {
    public static ReadyPayload read(ByteBufferInputStream input) { return new ReadyPayload(); }
    public void write(ByteBufferOutputStream output) {

    }
}
