package com.github.minecraft_ta.totaldebug.companionApp.chunkGrid;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.network.chunkGrid.ChunkGridDataMessage;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldServer;

import java.util.HashMap;
import java.util.Iterator;
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
            for (Iterator<Map.Entry<UUID, ChunkGridRequestInfo>> iterator = this.players.entrySet().iterator(); iterator.hasNext(); ) {
                Map.Entry<UUID, ChunkGridRequestInfo> entry = iterator.next();
                UUID uuid = entry.getKey();
                ChunkGridRequestInfo chunkGridRequestInfo = entry.getValue();

                if (chunkGridRequestInfo == ChunkGridRequestInfo.INVALID)
                    continue;

                int width = chunkGridRequestInfo.getWidth();
                int height = chunkGridRequestInfo.getHeight();

                WorldServer world = TotalDebug.PROXY.getSidedHandler().getServer().getWorld(chunkGridRequestInfo.getDimension());

                Long2ByteMap stateMap = new Long2ByteOpenHashMap();

                for (int i = 0; i < width; i++) {
                    int chunkX = chunkGridRequestInfo.getMinChunkX() + i;
                    int chunkCenterX = chunkX * 16 + 8;

                    for (int j = 0; j < height; j++) {
                        int chunkZ = chunkGridRequestInfo.getMinChunkZ() + j;
                        int chunkCenterZ = chunkZ * 16 + 8;

                        blockPos.setPos(chunkCenterX, 1, chunkCenterZ);
                        boolean isChunkLoaded = world.getChunkProvider().chunkExists(chunkX, chunkZ);

                        long posLong = (long) chunkX << 32 | (chunkZ & 0xffffffffL);
                        if (isChunkLoaded && world.isSpawnChunk(chunkX, chunkZ))
                            stateMap.put(posLong, SPAWN_CHUNK);
                        else if (isChunkLoaded)
                            stateMap.put(posLong, LAZY_CHUNK);
                    }
                }

                //...
                EntityPlayerMP player = TotalDebug.PROXY.getSidedHandler().getServer().getPlayerList().getPlayerByUUID(uuid);

                if (player == null) {
                    iterator.remove();
                    continue;
                }

                TotalDebug.INSTANCE.network.sendTo(new ChunkGridDataMessage(chunkGridRequestInfo, stateMap), player);
            }
        }

        blockPos.release();
    }

    public void setRequestInfo(UUID uniqueID, ChunkGridRequestInfo requestInfo) {
        synchronized (this.players) {
            this.players.put(uniqueID, requestInfo);
        }
    }
}
