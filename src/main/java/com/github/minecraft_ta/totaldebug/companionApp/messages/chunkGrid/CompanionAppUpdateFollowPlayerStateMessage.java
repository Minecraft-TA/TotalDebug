package com.github.minecraft_ta.totaldebug.companionApp.messages.chunkGrid;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.tth05.scnet.message.AbstractMessageIncoming;
import com.github.tth05.scnet.util.ByteBufferInputStream;

public class CompanionAppUpdateFollowPlayerStateMessage extends AbstractMessageIncoming {

    public static final byte STATE_NONE = 0;
    public static final byte STATE_ONCE = 1;
    public static final byte STATE_FOLLOW = 2;

    private byte state;

    @Override
    public void read(ByteBufferInputStream messageStream) {
        this.state = messageStream.readByte();
    }

    public static void handle(CompanionAppUpdateFollowPlayerStateMessage message) {
        byte state = message.state;

        switch (state) {
            case STATE_NONE:
            case STATE_FOLLOW:
                TotalDebug.PROXY.getChunkGridManagerClient().setFollowPlayer(state == STATE_FOLLOW);
                break;
            case STATE_ONCE:
                TotalDebug.PROXY.getChunkGridManagerClient().centerOnPlayer();
                break;
        }
    }
}
