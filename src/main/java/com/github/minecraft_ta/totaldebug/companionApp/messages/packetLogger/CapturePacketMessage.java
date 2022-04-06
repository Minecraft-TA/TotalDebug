package com.github.minecraft_ta.totaldebug.companionApp.messages.packetLogger;

import com.github.tth05.scnet.message.AbstractMessageIncoming;
import com.github.tth05.scnet.util.ByteBufferInputStream;

public class CapturePacketMessage extends AbstractMessageIncoming {

    private String packet;
    private boolean remove;

    @Override
    public void read(ByteBufferInputStream messageStream) {
        this.packet = messageStream.readString();
        this.remove = messageStream.readBoolean();
    }

    public boolean isRemove() {
        return remove;
    }

    public String getPacket() {
        return this.packet;
    }
}
