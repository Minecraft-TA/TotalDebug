package com.github.minecraft_ta.totaldebug.network.chunkGrid;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.companionApp.chunkGrid.ChunkGridRequestInfo;
import com.github.minecraft_ta.totaldebug.companionApp.messages.chunkGrid.CompanionAppChunkGridDataMessage;
import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class ChunkGridDataMessage implements IMessage, IMessageHandler<ChunkGridDataMessage, IMessage> {

    private ChunkGridRequestInfo requestInfo;
    private byte[][] stateArray;

    public ChunkGridDataMessage() {
    }

    public ChunkGridDataMessage(ChunkGridRequestInfo requestInfo, byte[][] stateArray) {
        this.requestInfo = requestInfo;
        this.stateArray = stateArray;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.requestInfo = ChunkGridRequestInfo.fromBytes(buf);
        short width = buf.readShort();
        short height = buf.readShort();

        this.stateArray = new byte[width][height];
        for (int i = 0; i < width; i++) {
            buf.readBytes(this.stateArray[i]);
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        this.requestInfo.toBytes(buf);
        buf.writeShort(this.stateArray.length);
        buf.writeShort(this.stateArray[0].length);

        for (byte[] bytes : this.stateArray) {
            buf.writeBytes(bytes);
        }
    }

    @Override
    public IMessage onMessage(ChunkGridDataMessage message, MessageContext ctx) {
        TotalDebug.PROXY.getCompanionApp().getClient().getMessageProcessor()
                .enqueueMessage(new CompanionAppChunkGridDataMessage(message.requestInfo, message.stateArray));
        return null;
    }
}
