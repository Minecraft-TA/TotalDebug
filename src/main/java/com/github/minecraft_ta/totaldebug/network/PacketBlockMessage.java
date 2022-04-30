package com.github.minecraft_ta.totaldebug.network;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.handler.ChannelInputHandler;
import com.github.minecraft_ta.totaldebug.handler.PacketBlocker;
import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

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
        PacketBlocker packetBlocker = ChannelInputHandler.packetBlockers.get(ctx.getServerHandler().player.getUniqueID());
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
