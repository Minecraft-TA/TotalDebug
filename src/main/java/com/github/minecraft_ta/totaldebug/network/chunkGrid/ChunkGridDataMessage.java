package com.github.minecraft_ta.totaldebug.network.chunkGrid;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.companionApp.chunkGrid.ChunkGridRequestInfo;
import com.github.minecraft_ta.totaldebug.companionApp.messages.chunkGrid.CompanionAppChunkGridDataMessage;
import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

public class ChunkGridDataMessage implements IMessage, IMessageHandler<ChunkGridDataMessage, IMessage> {

    private ChunkGridRequestInfo requestInfo;
    private Long2ByteMap stateMap;

    public ChunkGridDataMessage() {
    }

    public ChunkGridDataMessage(ChunkGridRequestInfo requestInfo, Long2ByteMap stateMap) {
        this.requestInfo = requestInfo;
        this.stateMap = stateMap;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.requestInfo = ChunkGridRequestInfo.fromBytes(buf);
        int count = buf.readInt();

        this.stateMap = new Long2ByteOpenHashMap();
        for (int i = 0; i < count; i++) {
            this.stateMap.put(buf.readLong(), buf.readByte());
        }
    }

    @Override
    public void toBytes(ByteBuf buf) {
        this.requestInfo.toBytes(buf);
        buf.writeInt(this.stateMap.size());

        this.stateMap.forEach((k, v) -> {
            buf.writeLong(k);
            buf.writeByte(v);
        });
    }

    @Override
    public IMessage onMessage(ChunkGridDataMessage message, MessageContext ctx) {
        TotalDebug.PROXY.getCompanionApp().getClient().getMessageProcessor()
                .enqueueMessage(new CompanionAppChunkGridDataMessage(message.requestInfo, message.stateMap));
        return null;
    }
}
