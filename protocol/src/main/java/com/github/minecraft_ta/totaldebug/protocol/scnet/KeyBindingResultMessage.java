package com.github.minecraft_ta.totaldebug.protocol.scnet;

import com.github.minecraft_ta.totaldebug.protocol.message.KeyBindingResultPayload;
import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

/** The game answers a {@link SetKeyBindingMessage}. */
public final class KeyBindingResultMessage extends AbstractMessage {
    private KeyBindingResultPayload payload;

    public KeyBindingResultMessage() {
    }

    public KeyBindingResultMessage(KeyBindingResultPayload payload) {
        this.payload = payload;
    }

    @Override
    public void read(ByteBufferInputStream input) {
        this.payload = KeyBindingResultPayload.read(input);
    }

    @Override
    public void write(ByteBufferOutputStream output) {
        this.payload.write(output);
    }

    public KeyBindingResultPayload payload() {
        return this.payload;
    }
}
