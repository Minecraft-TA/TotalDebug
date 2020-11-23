package com.github.minecraft_ta.totaldebug.network;

import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class TickTimeMessage implements IMessage, IMessageHandler<TicksPerSecondMessage, IMessage> {

    private double mspt;
    private double tps;

    @Override
    public void fromBytes(ByteBuf buf) {

    }

    @Override
    public void toBytes(ByteBuf buf) {

    }

    @Override
    public IMessage onMessage(TicksPerSecondMessage message, MessageContext ctx) {
        return null;
    }
}
