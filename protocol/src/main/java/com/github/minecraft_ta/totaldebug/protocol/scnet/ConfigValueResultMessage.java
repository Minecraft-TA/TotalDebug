package com.github.minecraft_ta.totaldebug.protocol.scnet;

import com.github.minecraft_ta.totaldebug.protocol.message.ConfigValueResultPayload;
import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

/** The game answers a {@link SetConfigValueMessage}. */
public final class ConfigValueResultMessage extends AbstractMessage {
    private ConfigValueResultPayload payload;

    public ConfigValueResultMessage() {
    }

    public ConfigValueResultMessage(ConfigValueResultPayload payload) {
        this.payload = payload;
    }

    @Override
    public void read(ByteBufferInputStream input) {
        this.payload = ConfigValueResultPayload.read(input);
    }

    @Override
    public void write(ByteBufferOutputStream output) {
        this.payload.write(output);
    }

    public ConfigValueResultPayload payload() {
        return this.payload;
    }
}
