package com.github.minecraft_ta.totaldebug.companionApp.messages.packetLogger;

import com.github.minecraft_ta.totaldebug.util.ObjectToJsonHelper;
import com.github.tth05.scnet.message.AbstractMessageOutgoing;
import com.github.tth05.scnet.util.ByteBufferOutputStream;
import com.google.gson.JsonObject;

public class PacketContentMessage extends AbstractMessageOutgoing {

    private final String packetName;
    private final String channel;
    private final int bytes;
    private final Object packet;

    public PacketContentMessage(String packetName, String channel,int bytes, Object packet) {
        this.packetName = packetName;
        this.channel = channel;
        this.bytes = bytes;
        this.packet = packet;
    }

    @Override
    public void write(ByteBufferOutputStream messageStream) {
        messageStream.writeString(packetName);
        messageStream.writeString(channel);
        messageStream.writeInt(bytes);
        try {
            messageStream.writeString(ObjectToJsonHelper.objectToJson(packet).toString());
        } catch (StackOverflowError e) {
            JsonObject jsonObject = new JsonObject();
            jsonObject.addProperty("error", "StackOverflowError");
            messageStream.writeString(jsonObject.toString());
            e.printStackTrace();
        }
    }
}
