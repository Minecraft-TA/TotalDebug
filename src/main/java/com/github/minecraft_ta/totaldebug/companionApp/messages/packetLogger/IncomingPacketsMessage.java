package com.github.minecraft_ta.totaldebug.companionApp.messages.packetLogger;

import com.github.tth05.scnet.message.AbstractMessageOutgoing;
import com.github.tth05.scnet.util.ByteBufferOutputStream;
import org.apache.commons.lang3.tuple.Pair;

import java.util.Map;

public class IncomingPacketsMessage extends AbstractMessageOutgoing {

    private final Map<String, Pair<Integer, Integer>> incomingPackets;

    public IncomingPacketsMessage(Map<String, Pair<Integer, Integer>> incomingPackets) {
        this.incomingPackets = incomingPackets;
    }

    @Override
    public void write(ByteBufferOutputStream messageStream) {
        messageStream.writeInt(incomingPackets.size());
        incomingPackets.forEach((s, pair) -> {
            messageStream.writeString(s);
            messageStream.writeInt(pair.getKey());
            messageStream.writeInt(pair.getValue());
        });
    }
}
