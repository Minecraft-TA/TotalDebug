package com.github.minecraft_ta.totaldebug.network;

import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class LoadedResultMessage implements IMessage, IMessageHandler<LoadedResultMessage, IMessage> {

    public LoadedResultMessage() {

    }

    @Override
    public void fromBytes(ByteBuf buf) {

    }

    @Override
    public void toBytes(ByteBuf buf) {

    }

    @Override
    public IMessage onMessage(LoadedResultMessage message, MessageContext ctx) {
        return null;
    }
}
