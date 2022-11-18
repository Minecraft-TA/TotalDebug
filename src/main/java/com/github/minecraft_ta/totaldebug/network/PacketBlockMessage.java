package com.github.minecraft_ta.totaldebug.network;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.handler.ChannelInputHandler;
import com.github.minecraft_ta.totaldebug.handler.PacketBlocker;
import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

import java.util.ArrayList;
import java.util.List;

public class PacketBlockMessage implements IMessage, IMessageHandler<PacketBlockMessage, IMessage> {

    private List<String> blockedPackets;

    public PacketBlockMessage() {
    }

    public PacketBlockMessage(List<String> blockedPackets) {
        this.blockedPackets = blockedPackets;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        int length = buf.readInt();
        this.blockedPackets = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            this.blockedPackets.add(ByteBufUtils.readUTF8String(buf));
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeInt(this.blockedPackets.size());
        for (String s : this.blockedPackets) {
            ByteBufUtils.writeUTF8String(buf, s);
        }
    }

    @Override
    public IMessage onMessage(PacketBlockMessage message, MessageContext ctx) {
        PacketBlocker packetBlocker = ChannelInputHandler.packetBlockers.get(ctx.getServerHandler().playerEntity.getUniqueID());
        packetBlocker.clearBlockedPackets();
        for (String blockedPacket : message.blockedPackets) {
            try {
                Class<?> clazz = Class.forName(blockedPacket);
                packetBlocker.addBlockedPacket(clazz);
            } catch (ClassNotFoundException e) {
                TotalDebug.LOGGER.error("Failed to block packet " + blockedPacket + ": Class not found");
            }
        }
        return null;
    }
}
