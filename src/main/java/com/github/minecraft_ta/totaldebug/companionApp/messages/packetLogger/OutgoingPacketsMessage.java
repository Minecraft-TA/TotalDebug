package com.github.minecraft_ta.totaldebug.companionApp.messages.packetLogger;

import com.github.tth05.scnet.message.AbstractMessageOutgoing;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

import java.util.HashMap;

public class OutgoingPacketsMessage extends AbstractMessageOutgoing {

    private final HashMap<String, Integer> outgoingPackets;

    public OutgoingPacketsMessage(HashMap<String, Integer> outgoingPackets) {
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
