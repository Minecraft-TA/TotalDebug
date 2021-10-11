package com.github.minecraft_ta.totaldebug.companionApp.messages.chunkGrid;

import com.github.minecraft_ta.totaldebug.companionApp.chunkGrid.ChunkGridRequestInfo;
import com.github.tth05.scnet.message.AbstractMessageOutgoing;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;

public class CompanionAppChunkGridDataMessage extends AbstractMessageOutgoing {

    private ChunkGridRequestInfo requestInfo;
    private Long2ByteMap stateMap;

    public CompanionAppChunkGridDataMessage() {}

    public CompanionAppChunkGridDataMessage(ChunkGridRequestInfo requestInfo, Long2ByteMap stateMap) {
        this.requestInfo = requestInfo;
        this.stateMap = stateMap;
    }

    @Override
    public void write(ByteBufferOutputStream messageStream) {
        this.requestInfo.toBytes(messageStream);
        messageStream.writeInt(this.stateMap.size());

        this.stateMap.forEach((k, v) -> {
            messageStream.writeLong(k);
            messageStream.writeByte(v);
        });
    }

    @Override
    public void read(ByteBufferInputStream messageStream) {
        this.requestInfo = ChunkGridRequestInfo.fromBytes(messageStream);

        int size = messageStream.readInt();
        this.stateMap = new Long2ByteOpenHashMap(size);
        for (int i = 0; i < size; i++) {
            this.stateMap.put(messageStream.readLong(), messageStream.readByte());
        }
    }
}
