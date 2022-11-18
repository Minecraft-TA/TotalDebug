package com.github.minecraft_ta.totaldebug.companionApp.chunkGrid;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.companionApp.messages.chunkGrid.CompanionAppChunkGridDataMessage;
import com.github.minecraft_ta.totaldebug.network.CompanionAppForwardedMessage;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.WorldServer;
import net.minecraft.world.gen.ChunkProviderServer;
import nonapi.io.github.classgraph.reflection.ReflectionUtils;

import java.lang.reflect.Field;
import java.util.*;

public class ChunkGridManagerServer implements IChunkGridManager {

    private static final Field UNLOAD_QUEUE_FIELD;
    static {
        try {
            UNLOAD_QUEUE_FIELD = ChunkProviderServer.class.getDeclaredField("chunksToUnload");
            UNLOAD_QUEUE_FIELD.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

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

                //Prevent causing a dimension load -> MinecraftServer#getWorld(int)
                WorldServer world = null;
                for (WorldServer serverWorld : TotalDebug.PROXY.getSidedHandler().getServer().worldServers) {
                    if (serverWorld.provider.dimensionId == chunkGridRequestInfo.getDimension()) {
                        world = serverWorld;
                        break;
                    }
                }
                if (world == null)
                    continue;

                ChunkCoordinates spawnPoint = world.getSpawnPoint();
                Long2ByteMap stateMap = new Long2ByteOpenHashMap();
                Set<Long> chunksToUnload = (Set<Long>) ReflectionUtils.getFieldVal(false, world.getChunkProvider(), UNLOAD_QUEUE_FIELD);

                for (int i = 0; i < width; i++) {
                    int chunkX = chunkGridRequestInfo.getMinChunkX() + i;

                    for (int j = 0; j < height; j++) {
                        int chunkZ = chunkGridRequestInfo.getMinChunkZ() + j;
                        boolean isChunkLoaded = world.getChunkProvider().chunkExists(chunkX, chunkZ);

                        long posLong = (long) chunkX << 32 | (chunkZ & 0xffffffffL);
                        if (isChunkLoaded && isSpawnChunk(spawnPoint, chunkX, chunkZ) && world.provider.dimensionId == 0)
                            stateMap.put(posLong, SPAWN_CHUNK);
                        else if (world.getPlayerManager().func_152621_a(chunkX, chunkZ))
                            stateMap.put(posLong, PLAYER_LOADED_CHUNK);
                        else if (isChunkLoaded && chunksToUnload.contains(ChunkCoordIntPair.chunkXZ2Int(chunkX, chunkZ)))
                            stateMap.put(posLong, QUEUED_TO_UNLOAD_CHUNK);
                        else if (isChunkLoaded)
                            stateMap.put(posLong, LAZY_CHUNK);
                    }
                }

                //TODO: Is this the best way to do this????
                EntityPlayerMP player = null;
                for (Object o : TotalDebug.PROXY.getSidedHandler().getServer().getConfigurationManager().playerEntityList) {
                    EntityPlayerMP entityPlayerMP = (EntityPlayerMP) o;
                    if (entityPlayerMP.getUniqueID().equals(uuid)) {
                        player = entityPlayerMP;
                        break;
                    }
                }

                if (player == null) {
                    iterator.remove();
                    continue;
                }

                TotalDebug.INSTANCE.network.sendTo(new CompanionAppForwardedMessage(new CompanionAppChunkGridDataMessage(chunkGridRequestInfo, stateMap)), player);
            }
        }
    }

    public void setRequestInfo(UUID uniqueID, ChunkGridRequestInfo requestInfo) {
        synchronized (this.players) {
            this.players.put(uniqueID, requestInfo);
        }
    }

    private static boolean isSpawnChunk(ChunkCoordinates spawnPoint, int chunkX, int chunkZ) {
        int k = chunkX + 8 - spawnPoint.posX;
        int l = chunkZ + 8 - spawnPoint.posZ;
        short short1 = 8;

        return k >= -short1 && k <= short1 && l >= -short1 && l <= short1;
    }
}
