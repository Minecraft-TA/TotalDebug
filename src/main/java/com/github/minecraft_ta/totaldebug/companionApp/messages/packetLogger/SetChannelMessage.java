package com.github.minecraft_ta.totaldebug.companionApp.messages.packetLogger;

import com.github.tth05.scnet.message.AbstractMessageIncoming;
import com.github.tth05.scnet.util.ByteBufferInputStream;

public class SetChannelMessage extends AbstractMessageIncoming {

    private String channel;

    @Override
    public void read(ByteBufferInputStream messageStream) {
        channel = messageStream.readString();
    }

    public String getChannel() {
        return channel;
    }
}
