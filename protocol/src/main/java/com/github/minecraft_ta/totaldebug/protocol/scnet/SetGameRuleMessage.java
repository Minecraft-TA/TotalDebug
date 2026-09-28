package com.github.minecraft_ta.totaldebug.protocol.scnet;

import com.github.minecraft_ta.totaldebug.protocol.message.SetGameRulePayload;
import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

/** Companion asks the game to set a game rule of the world it has open. */
public final class SetGameRuleMessage extends AbstractMessage {
    private SetGameRulePayload payload;

    public SetGameRuleMessage() {
    }

    public SetGameRuleMessage(SetGameRulePayload payload) {
        this.payload = payload;
    }

    @Override
    public void read(ByteBufferInputStream input) {
        this.payload = SetGameRulePayload.read(input);
    }

    @Override
    public void write(ByteBufferOutputStream output) {
        this.payload.write(output);
    }

    public SetGameRulePayload payload() {
        return this.payload;
    }
}
