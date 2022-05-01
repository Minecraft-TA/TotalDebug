package com.github.minecraft_ta.totaldebug.companionApp.messages.packetLogger;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.tth05.scnet.message.AbstractMessageIncoming;
import com.github.tth05.scnet.util.ByteBufferInputStream;

public class BlockPacketMessage extends AbstractMessageIncoming {

    @Override
    public void read(ByteBufferInputStream messageStream) {
        TotalDebug.INSTANCE.config.toggleBlockedPacket(messageStream.readString());
    }
}
