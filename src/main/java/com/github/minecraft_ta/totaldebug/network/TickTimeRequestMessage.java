package com.github.minecraft_ta.totaldebug.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class TickTimeRequestMessage implements IMessage, IMessageHandler<TickTimeRequestMessage, IMessage> {

    @Override
    public void fromBytes(ByteBuf buf) {

    }

    @Override
    public void toBytes(ByteBuf buf) {

    }

    @Override
    public IMessage onMessage(TickTimeRequestMessage message, MessageContext ctx) {
        MinecraftServer server = ctx.getServerHandler().player.server;

        double meanTickTime = Math.round((mean(server.tickTimeArray) * 1.0E-6D) * 10.0) / 10.0;
        double meanTPS = Math.round(Math.min(1000 / meanTickTime, 20) * 10.0) / 10.0;

        return new TickTimeResultMessage(meanTickTime, meanTPS);
    }

    private long mean(long[] values) {
        long sum = 0L;
        for (long v : values) {
            sum += v;
        }
        return sum / values.length;
    }
}
