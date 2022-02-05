package com.github.minecraft_ta.totaldebug.companionApp.messages.packetLogger;

import com.github.tth05.scnet.message.AbstractMessageOutgoing;
import com.github.tth05.scnet.util.ByteBufferOutputStream;
import com.google.gson.Gson;

public class PacketContentMessage extends AbstractMessageOutgoing {

    private static final Gson GSON = new Gson();
    private final String packetName;
    private final Object packet;

    public PacketContentMessage(String packetName, Object packet) {
        this.packetName = packetName;
        this.packet = packet;
    }

    @Override
    public void write(ByteBufferOutputStream messageStream) {
        messageStream.writeString(packetName);
        messageStream.writeString(GSON.toJson(packet));
    }
}
