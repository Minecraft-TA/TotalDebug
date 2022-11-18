package com.github.minecraft_ta.totaldebug.companionApp.chunkGrid;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.companionApp.messages.chunkGrid.CompanionAppChunkGridRequestInfoUpdateMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;

public class ChunkGridManagerClient implements IChunkGridManager {

    private boolean followPlayer;
    private int lastChunkX;
    private int lastChunkZ;

    private ChunkGridRequestInfo currentChunkGridRequestInfo;

    @Override
    public void update() {
        if (!this.followPlayer)
            return;

        int currentChunkX = Minecraft.getMinecraft().thePlayer.chunkCoordX;
        int currentChunkZ = Minecraft.getMinecraft().thePlayer.chunkCoordZ;

        if (lastChunkX == currentChunkX && lastChunkZ == currentChunkZ)
            return;

        this.lastChunkX = currentChunkX;
        this.lastChunkZ = currentChunkZ;
        centerOnPlayer();
    }

    public void centerOnPlayer() {
        EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
        ChunkGridRequestInfo requestInfo = TotalDebug.PROXY.getChunkGridManagerClient().getCurrentChunkGridRequestInfo();

        int width = requestInfo.getWidth(), height = requestInfo.getHeight();

        requestInfo.moveTo(player.chunkCoordX - width / 2, player.chunkCoordZ - height / 2);
        requestInfo.setDimension(player.dimension);
        TotalDebug.PROXY.getCompanionApp().getClient().getMessageProcessor().enqueueMessage(new CompanionAppChunkGridRequestInfoUpdateMessage(requestInfo));
    }

    public void setCurrentChunkGridRequestInfo(ChunkGridRequestInfo currentChunkGridRequestInfo) {
        this.currentChunkGridRequestInfo = currentChunkGridRequestInfo;
    }

    public ChunkGridRequestInfo getCurrentChunkGridRequestInfo() {
        return currentChunkGridRequestInfo;
    }

    public void setFollowPlayer(boolean followPlayer) {
        this.followPlayer = followPlayer;
    }
}
