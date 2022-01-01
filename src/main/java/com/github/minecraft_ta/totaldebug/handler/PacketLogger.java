package com.github.minecraft_ta.totaldebug.handler;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.companionApp.messages.packetLogger.IncomingPacketsMessage;
import com.github.minecraft_ta.totaldebug.companionApp.messages.packetLogger.OutgoingPacketsMessage;
import com.github.tth05.scnet.Client;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import net.minecraft.network.Packet;

import java.util.HashMap;
import java.util.Map;

public class PacketLogger extends ChannelDuplexHandler {

    private final Map<String, Integer> incomingPackets = new HashMap<>();
    private final Map<String, Integer> outgoingPackets = new HashMap<>();
    private boolean incomingActive;
    private boolean outgoingActive;

    public void update() {
        final Client client = TotalDebug.PROXY.getCompanionApp().getClient();
        if (incomingActive) {
            client.getMessageProcessor().enqueueMessage(new IncomingPacketsMessage(incomingPackets));
        }
        if (outgoingActive) {
            client.getMessageProcessor().enqueueMessage(new OutgoingPacketsMessage(outgoingPackets));
        }
    }

    public void setIncomingActive(boolean incomingActive) {
        this.incomingActive = incomingActive;
    }

    public void setOutgoingActive(boolean outgoingActive) {
        this.outgoingActive = outgoingActive;
    }

    public void clear() {
        incomingPackets.clear();
        outgoingPackets.clear();
    }

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

}
