package com.github.minecraft_ta.totaldebug.companionApp.messages.packetLogger;

import com.github.tth05.scnet.message.AbstractMessageOutgoing;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

import java.util.HashMap;

public class IncomingPacketsMessage extends AbstractMessageOutgoing {

    private final HashMap<String, Integer> incomingPackets;

    public IncomingPacketsMessage(HashMap<String, Integer> incomingPackets) {
        this.incomingPackets = incomingPackets;
    }

    @Override
    public void write(ByteBufferOutputStream messageStream) {
        messageStream.writeInt(incomingPackets.size());
        incomingPackets.forEach((s, integer) -> {
            messageStream.writeString(s);
            messageStream.writeInt(integer);
        });
    }
}
