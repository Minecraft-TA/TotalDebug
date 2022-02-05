package com.github.minecraft_ta.totaldebug.companionApp.messages.packetLogger;

import com.github.tth05.scnet.message.AbstractMessageOutgoing;
import com.github.tth05.scnet.util.ByteBufferOutputStream;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public class PacketContentMessage extends AbstractMessageOutgoing {

    private static final GsonBuilder BUILDER = new GsonBuilder();
    private static final Gson GSON = BUILDER.serializeNulls().create();
    private final String packetName;
    private final String channel;
    private final Object packet;

    public PacketContentMessage(String packetName, String channel, Object packet) {
        this.packetName = packetName;
        this.channel = channel;
        this.packet = packet;
    }

    @Override
    public void write(ByteBufferOutputStream messageStream) {
        messageStream.writeString(packetName);
        messageStream.writeString(channel);
        messageStream.writeString(GSON.toJson(packet));
    }
}
