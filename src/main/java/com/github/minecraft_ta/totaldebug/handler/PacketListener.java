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

public class PacketListener extends ChannelDuplexHandler {

    private static final HashMap<String, Integer> incomingPackets = new HashMap<>();
    private static final HashMap<String, Integer> outgoingPackets = new HashMap<>();
    private static boolean incomingActive;
    private static boolean outgoingActive;

    public static void update() {
        final Client client = TotalDebug.PROXY.getCompanionApp().getClient();
        if (incomingActive) {
            client.getMessageProcessor().enqueueMessage(new IncomingPacketsMessage(incomingPackets));
        }
        if (outgoingActive) {
            client.getMessageProcessor().enqueueMessage(new OutgoingPacketsMessage(outgoingPackets));
        }
    }

    public static void toggleIncomingActive() {
        incomingActive = !incomingActive;
        if (!incomingActive) incomingPackets.clear();
    }

    public static void toggleOutgoingActive() {
        outgoingActive = !outgoingActive;
        if (!outgoingActive) outgoingPackets.clear();
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
