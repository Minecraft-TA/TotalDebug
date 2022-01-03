package com.github.minecraft_ta.totaldebug.companionApp.messages.packetLogger;

import com.github.tth05.scnet.message.AbstractMessageOutgoing;
import com.github.tth05.scnet.util.ByteBufferOutputStream;
import org.apache.commons.lang3.tuple.Pair;

import java.util.Map;

public class OutgoingPacketsMessage extends AbstractMessageOutgoing {

    private final Map<String, Pair<Integer, Integer>> outgoingPackets;

    public OutgoingPacketsMessage(Map<String, Pair<Integer, Integer>> outgoingPackets) {
        this.outgoingPackets = outgoingPackets;
    }

    @Override
    public void write(ByteBufferOutputStream messageStream) {
        messageStream.writeInt(outgoingPackets.size());
        outgoingPackets.forEach((s, pair) -> {
            messageStream.writeString(s);
            messageStream.writeInt(pair.getKey());
            messageStream.writeInt(pair.getLeft());
        });
    }
}
