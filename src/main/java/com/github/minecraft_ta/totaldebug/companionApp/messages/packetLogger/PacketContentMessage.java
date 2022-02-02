package com.github.minecraft_ta.totaldebug.companionApp.messages.packetLogger;

import com.github.tth05.scnet.message.AbstractMessageOutgoing;
import com.github.tth05.scnet.util.ByteBufferOutputStream;
import net.minecraft.nbt.NBTTagCompound;

public class PacketContentMessage extends AbstractMessageOutgoing {

    private final String packetName;
    private final NBTTagCompound packetData;

    public PacketContentMessage(String packetName, NBTTagCompound packetData) {
        this.packetName = packetName;
        this.packetData = packetData;
    }

    @Override
    public void write(ByteBufferOutputStream messageStream) {
        messageStream.writeString(packetName);
        messageStream.writeString(packetData.toString());
    }
}
