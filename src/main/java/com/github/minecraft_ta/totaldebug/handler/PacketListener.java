package com.github.minecraft_ta.totaldebug.handler;

import com.github.minecraft_ta.totaldebug.event.PacketEvent;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import net.minecraft.network.Packet;
import net.minecraftforge.common.MinecraftForge;

public class PacketListener extends ChannelDuplexHandler {

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        boolean get = true;

        if (msg instanceof Packet) {
            PacketEvent.Incoming inPacket = new PacketEvent.Incoming((Packet<?>) msg);
            MinecraftForge.EVENT_BUS.post(inPacket);
            if (inPacket.isCanceled()) {
                get = false;
            }
            msg = inPacket.getPacket();
        }
        if (get) super.channelRead(ctx, msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        boolean send = true;

        if (msg instanceof Packet) {
            PacketEvent.Outgoing outPacket = new PacketEvent.Outgoing((Packet<?>) msg);
            MinecraftForge.EVENT_BUS.post(outPacket);
            if (outPacket.isCanceled()) {
                send = false;
            }
            msg = outPacket.getPacket();
        }
        if (send)
            super.write(ctx, msg, promise);
    }
}
