package com.github.minecraft_ta.totaldebug.network;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;
import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class CompanionAppForwardedMessage implements IMessage, IMessageHandler<CompanionAppForwardedMessage, CompanionAppForwardedMessage> {

    private AbstractMessage message;

    public CompanionAppForwardedMessage() {
    }

    public CompanionAppForwardedMessage(AbstractMessage message) {
        this.message = message;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        try {
            this.message = (AbstractMessage) Class.forName(ByteBufUtils.readUTF8String(buf)).getConstructor().newInstance();
            ByteBufferInputStream stream = new ByteBufferInputStream(buf.readBytes(buf.readInt()).nioBuffer());
            this.message.read(stream);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        ByteBufUtils.writeUTF8String(buf, this.message.getClass().getName());

        ByteBufferOutputStream stream = new ByteBufferOutputStream();
        this.message.write(stream);

        byte[] messageBytes = stream.getBuffer().array();
        buf.writeInt(messageBytes.length);
        buf.writeBytes(messageBytes);
    }

    @Override
    public CompanionAppForwardedMessage onMessage(CompanionAppForwardedMessage message, MessageContext ctx) {
        TotalDebug.PROXY.getCompanionApp().getClient().getMessageProcessor().enqueueMessage(message.message);
        return null;
    }
}
