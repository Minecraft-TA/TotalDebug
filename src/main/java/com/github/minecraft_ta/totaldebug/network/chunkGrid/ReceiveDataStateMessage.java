package com.github.minecraft_ta.totaldebug.network.chunkGrid;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.companionApp.chunkGrid.ChunkGridManagerServer;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import io.netty.buffer.ByteBuf;

import java.util.UUID;

public class ReceiveDataStateMessage implements IMessage, IMessageHandler<ReceiveDataStateMessage, IMessage> {

    private boolean state;

    public ReceiveDataStateMessage() {
    }

    public ReceiveDataStateMessage(boolean state) {
        this.state = state;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.state = buf.readBoolean();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(this.state);
    }

    @Override
    public IMessage onMessage(ReceiveDataStateMessage message, MessageContext ctx) {
        UUID playerId = ctx.getServerHandler().playerEntity.getUniqueID();

        ChunkGridManagerServer chunkGridManager = TotalDebug.PROXY.getChunkGridManagerServer();
        if (message.state)
            chunkGridManager.addPlayer(playerId);
        else
            chunkGridManager.removePlayer(playerId);
        return null;
    }
}
