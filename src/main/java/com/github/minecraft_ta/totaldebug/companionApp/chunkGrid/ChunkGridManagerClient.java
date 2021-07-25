package com.github.minecraft_ta.totaldebug.companionApp.chunkGrid;

public class ChunkGridManagerClient implements IChunkGridManager {

    private boolean followPlayer;
    private ChunkGridRequestInfo currentChunkGridRequestInfo;

    @Override
    public void update() {
        //Update min-max if follow player is on and send to server if anything changed
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
