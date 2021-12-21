package com.github.minecraft_ta.totaldebug.handler;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import net.minecraft.network.Packet;

import java.util.HashMap;

public class PacketListener extends ChannelDuplexHandler {

    private boolean incomingActive;
    private boolean outgoingActive;

    private HashMap<String, Integer> incomingPackets = new HashMap<>();
    private HashMap<String, Integer> outgoingPackets = new HashMap<>();

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (incomingActive && msg instanceof Packet) {
            incomingPackets.merge(msg.getClass().getName(), 1, Integer::sum);
        }
        super.channelRead(ctx, msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (outgoingActive && msg instanceof Packet) {
            outgoingPackets.merge(msg.getClass().getName(), 1, Integer::sum);
        }
        super.write(ctx, msg, promise);
    }

    public void activateIncoming() {
        this.incomingActive = true;
    }

    public void deactivateIncoming() {
        this.incomingPackets.clear();
        this.incomingActive = false;
    }

    public void activateOutgoing() {
        this.outgoingActive = true;
    }

    public void deactivateOutgoing() {
        this.outgoingPackets.clear();
        this.outgoingActive = false;
    }

}
