package com.github.minecraft_ta.totaldebug.companionApp.messages.chunkGrid;

import com.github.minecraft_ta.totaldebug.companionApp.chunkGrid.ChunkGridRequestInfo;
import com.github.tth05.scnet.message.AbstractMessageOutgoing;
import com.github.tth05.scnet.util.ByteBufferOutputStream;

public class CompanionAppChunkGridDataMessage extends AbstractMessageOutgoing {

    private ChunkGridRequestInfo requestInfo;
    private byte[][] stateArray;

    public CompanionAppChunkGridDataMessage() {}

    public CompanionAppChunkGridDataMessage(ChunkGridRequestInfo requestInfo, byte[][] stateArray) {
        this.requestInfo = requestInfo;
        this.stateArray = stateArray;
    }

    @Override
    public void write(ByteBufferOutputStream messageStream) {
        this.requestInfo.toBytes(messageStream);
        messageStream.writeShort((short) this.stateArray.length);
        messageStream.writeShort((short) this.stateArray[0].length);

        for (byte[] bytes : this.stateArray) {
            messageStream.writeByteArray(bytes);
        }
    }
}
