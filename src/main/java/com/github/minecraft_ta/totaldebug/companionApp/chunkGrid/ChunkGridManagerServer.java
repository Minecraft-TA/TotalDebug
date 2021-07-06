package com.github.minecraft_ta.totaldebug.companionApp.chunkGrid;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.network.chunkGrid.ChunkGridDataMessage;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ChunkGridManagerServer implements IChunkGridManager {

    private final Map<UUID, ChunkGridRequestInfo> players = new HashMap<>();

    public void addPlayer(UUID uuid) {
        synchronized (this.players) {
            this.players.put(uuid, ChunkGridRequestInfo.INVALID);
        }
    }

    public void removePlayer(UUID uuid) {
        synchronized (this.players) {
            this.players.remove(uuid);
        }
    }

    @Override
    public void update() {
        BlockPos.PooledMutableBlockPos blockPos = BlockPos.PooledMutableBlockPos.retain();

        //Gather and send chunk data
        synchronized (this.players) {
            this.players.forEach(((uuid, chunkGridRequestInfo) -> {
                if (chunkGridRequestInfo == ChunkGridRequestInfo.INVALID)
                    return;

                int width = chunkGridRequestInfo.getWidth();
                int height = chunkGridRequestInfo.getHeight();

                WorldServer world = TotalDebug.PROXY.getSidedHandler().getServer().getWorld(chunkGridRequestInfo.getDimension());

                byte[][] stateArray = new byte[width][height];

                for (int i = 0; i < width; i++) {
                    int chunkX = chunkGridRequestInfo.getMinChunkX() + i;
                    int chunkCenterX = chunkX * 16 + 8;

                    for (int j = 0; j < height; j++) {
                        int chunkZ = chunkGridRequestInfo.getMinChunkZ() + j;
                        int chunkCenterZ = chunkZ * 16 + 8;

                        blockPos.setPos(chunkCenterX, 1, chunkCenterZ);
                        boolean isBlockLoaded = world.isBlockLoaded(blockPos);
                        if (isBlockLoaded && world.isSpawnChunk(chunkX, chunkZ))
                            stateArray[i][j] = SPAWN_CHUNK;
                        else if (isBlockLoaded)
                            stateArray[i][j] = LAZY_CHUNK;
                    }
                }

                //...
                EntityPlayerMP player = TotalDebug.PROXY.getSidedHandler().getServer().getPlayerList().getPlayerByUUID(uuid);
                TotalDebug.INSTANCE.network.sendTo(new ChunkGridDataMessage(chunkGridRequestInfo, stateArray), player);
            }));
        }

        blockPos.release();
    }

    public void setRequestInfo(UUID uniqueID, ChunkGridRequestInfo requestInfo) {
        synchronized (this.players) {
            this.players.put(uniqueID, requestInfo);
        }
    }
}
