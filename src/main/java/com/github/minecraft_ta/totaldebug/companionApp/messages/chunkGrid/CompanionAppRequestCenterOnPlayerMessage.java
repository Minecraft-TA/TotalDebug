package com.github.minecraft_ta.totaldebug.companionApp.messages.chunkGrid;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.companionApp.chunkGrid.ChunkGridRequestInfo;
import com.github.tth05.scnet.message.AbstractMessageIncoming;
import com.github.tth05.scnet.util.ByteBufferInputStream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.util.math.BlockPos;

public class CompanionAppRequestCenterOnPlayerMessage extends AbstractMessageIncoming {

    @Override
    public void read(ByteBufferInputStream messageStream) {
    }

    public static void handle(CompanionAppRequestCenterOnPlayerMessage message) {
        EntityPlayerSP player = Minecraft.getMinecraft().player;
        BlockPos position = player.getPosition();
        ChunkGridRequestInfo requestInfo = TotalDebug.PROXY.getChunkGridManagerClient().getCurrentChunkGridRequestInfo();

        int width = requestInfo.getWidth(), height = requestInfo.getHeight();
        int chunkX = position.getX() >> 4, chunkZ = position.getZ() >> 4;

        requestInfo.moveTo(chunkX - width / 2, chunkZ - height / 2);
        requestInfo.setDimension(player.dimension);
        TotalDebug.PROXY.getCompanionApp().getClient().getMessageProcessor().enqueueMessage(new CompanionAppChunkGridRequestInfoUpdateMessage(requestInfo));
    }
}
