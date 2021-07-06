package com.github.minecraft_ta.totaldebug.companionApp.messages.chunkGrid;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.companionApp.chunkGrid.ChunkGridRequestInfo;
import com.github.minecraft_ta.totaldebug.network.chunkGrid.ChunkGridRequestInfoUpdateMessage;
import com.github.tth05.scnet.message.AbstractMessageIncoming;
import com.github.tth05.scnet.util.ByteBufferInputStream;

public class CompanionAppChunkGridRequestInfoUpdateMessage extends AbstractMessageIncoming {

    private ChunkGridRequestInfo chunkGridRequestInfo;

    @Override
    public void read(ByteBufferInputStream messageStream) {
        this.chunkGridRequestInfo = ChunkGridRequestInfo.fromBytes(messageStream);
    }

    public static void handle(CompanionAppChunkGridRequestInfoUpdateMessage message) {
        TotalDebug.INSTANCE.network.sendToServer(new ChunkGridRequestInfoUpdateMessage(message.chunkGridRequestInfo));
    }
}
