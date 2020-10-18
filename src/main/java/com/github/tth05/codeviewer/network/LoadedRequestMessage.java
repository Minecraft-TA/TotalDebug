package com.github.tth05.codeviewer.network;

import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class LoadedRequestMessage implements IMessage, IMessageHandler<LoadedRequestMessage, IMessage> {

    public LoadedRequestMessage() {
    }

    @Override
    public void fromBytes(ByteBuf buf) {

    }

    @Override
    public void toBytes(ByteBuf buf) {

    }

    @Override
    public IMessage onMessage(LoadedRequestMessage message, MessageContext ctx) {



        return null;
    }
}
