package com.github.minecraft_ta.totaldebug.protocol.scnet;

import com.github.minecraft_ta.totaldebug.protocol.message.SetKeyBindingPayload;
import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

/** Companion asks the game to put a key binding on a key. */
public final class SetKeyBindingMessage extends AbstractMessage {
    private SetKeyBindingPayload payload;

    public SetKeyBindingMessage() {
    }

    public SetKeyBindingMessage(int requestId, String name, String key, String modifier) {
        this.payload = new SetKeyBindingPayload(requestId, name, key, modifier);
    }

    @Override
    public void read(ByteBufferInputStream input) {
        this.payload = SetKeyBindingPayload.read(input);
    }

    @Override
    public void write(ByteBufferOutputStream output) {
        this.payload.write(output);
    }

    public SetKeyBindingPayload payload() {
        return this.payload;
    }
}
