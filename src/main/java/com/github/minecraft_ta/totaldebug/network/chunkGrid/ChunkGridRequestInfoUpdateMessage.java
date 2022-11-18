package com.github.minecraft_ta.totaldebug.network.chunkGrid;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.companionApp.chunkGrid.ChunkGridRequestInfo;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

public class ChunkGridRequestInfoUpdateMessage implements IMessage, IMessageHandler<ChunkGridRequestInfoUpdateMessage, IMessage> {

    private ChunkGridRequestInfo requestInfo;

    public ChunkGridRequestInfoUpdateMessage() {
    }

    public ChunkGridRequestInfoUpdateMessage(ChunkGridRequestInfo requestInfo) {
        this.requestInfo = requestInfo;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.requestInfo = ChunkGridRequestInfo.fromBytes(buf);
    }

    @Override
    public void toBytes(ByteBuf buf) {
        this.requestInfo.toBytes(buf);
    }

    @Override
    public IMessage onMessage(ChunkGridRequestInfoUpdateMessage message, MessageContext ctx) {
        TotalDebug.PROXY.getChunkGridManagerServer().setRequestInfo(ctx.getServerHandler().playerEntity.getUniqueID(), message.requestInfo);
        return null;
    }
}
