package com.github.minecraft_ta.totaldebug.companionApp.messages.packetLogger;

import com.github.tth05.scnet.message.AbstractMessageOutgoing;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

import java.util.Map;

public class OutgoingPacketsMessage extends AbstractMessageOutgoing {

    private final Map<String, Integer> outgoingPackets;

    public OutgoingPacketsMessage(Map<String, Integer> outgoingPackets) {
        this.outgoingPackets = outgoingPackets;
    }

    @Override
    public void write(ByteBufferOutputStream messageStream) {
        messageStream.writeInt(outgoingPackets.size());
        outgoingPackets.forEach((s, integer) -> {
            messageStream.writeString(s);
            messageStream.writeInt(integer);
        });
    }
}
