package com.github.minecraft_ta.totaldebug.protocol.scnet;

import com.github.minecraft_ta.totaldebug.protocol.message.GameRulesPayload;
import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

/** The game names the game rules of the world it has open. */
public final class GameRulesMessage extends AbstractMessage {
    private GameRulesPayload payload;

    public GameRulesMessage() {
    }

    public GameRulesMessage(GameRulesPayload payload) {
        this.payload = payload;
    }

    @Override
    public void read(ByteBufferInputStream input) {
        this.payload = GameRulesPayload.read(input);
    }

    @Override
    public void write(ByteBufferOutputStream output) {
        this.payload.write(output);
    }

    public GameRulesPayload payload() {
        return this.payload;
    }
}
