package com.github.minecraft_ta.totaldebug.companionApp.messages.chunkGrid;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.companionApp.chunkGrid.ChunkGridRequestInfo;
import com.github.minecraft_ta.totaldebug.network.chunkGrid.ChunkGridRequestInfoUpdateMessage;
import com.github.tth05.scnet.message.AbstractMessage;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

public class CompanionAppChunkGridRequestInfoUpdateMessage extends AbstractMessage {

    private ChunkGridRequestInfo chunkGridRequestInfo;

    public CompanionAppChunkGridRequestInfoUpdateMessage() {
    }

    public CompanionAppChunkGridRequestInfoUpdateMessage(ChunkGridRequestInfo chunkGridRequestInfo) {
        this.chunkGridRequestInfo = chunkGridRequestInfo;
    }

    @Override
    public void read(ByteBufferInputStream messageStream) {
        this.chunkGridRequestInfo = ChunkGridRequestInfo.fromBytes(messageStream);
    }

    @Override
    public void write(ByteBufferOutputStream messageStream) {
        this.chunkGridRequestInfo.toBytes(messageStream);
    }

    public static void handle(CompanionAppChunkGridRequestInfoUpdateMessage message) {
        TotalDebug.PROXY.getChunkGridManagerClient().setCurrentChunkGridRequestInfo(message.chunkGridRequestInfo);
        TotalDebug.INSTANCE.network.sendToServer(new ChunkGridRequestInfoUpdateMessage(message.chunkGridRequestInfo));
    }
}
