package com.github.minecraft_ta.totaldebug.proxy;

import com.github.minecraft_ta.totaldebug.DecompilationManager;
import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.block.tile.TickBlockTile;
import com.github.minecraft_ta.totaldebug.companionApp.CompanionApp;
import com.github.minecraft_ta.totaldebug.companionApp.chunkGrid.ChunkGridManagerClient;
import com.github.minecraft_ta.totaldebug.companionApp.chunkGrid.ChunkGridManagerServer;
import com.github.minecraft_ta.totaldebug.config.TotalDebugClientConfig;
import com.github.minecraft_ta.totaldebug.network.CompanionAppForwardedMessage;
import com.github.minecraft_ta.totaldebug.network.TickTimeRequestMessage;
import com.github.minecraft_ta.totaldebug.network.TickTimeResultMessage;
import com.github.minecraft_ta.totaldebug.network.TicksPerSecondMessage;
import com.github.minecraft_ta.totaldebug.network.chunkGrid.ChunkGridRequestInfoUpdateMessage;
import com.github.minecraft_ta.totaldebug.network.chunkGrid.ReceiveDataStateMessage;
import com.github.minecraft_ta.totaldebug.network.script.RunScriptOnServerMessage;
import com.github.minecraft_ta.totaldebug.network.script.StopScriptOnServerMessage;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.IFMLSidedHandler;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.registry.GameRegistry;
import net.minecraftforge.fml.relauncher.Side;

import java.util.ArrayList;
import java.util.List;

public class CommonProxy {

    private final ChunkGridManagerServer chunkGridManagerServer = new ChunkGridManagerServer();
    private final ChunkGridManagerClient chunkGridManagerClient = new ChunkGridManagerClient();

    protected final List<Runnable> preTickTasks = new ArrayList<>();
    protected final List<Runnable> postTickTasks = new ArrayList<>();

    public void preInit(FMLPreInitializationEvent e) {
        GameRegistry.registerTileEntity(TickBlockTile.class, new ResourceLocation(TotalDebug.MOD_ID, "tick_block_tile"));

        int id = 0;
        TotalDebug.INSTANCE.network.registerMessage(TicksPerSecondMessage.class, TicksPerSecondMessage.class, id++, Side.CLIENT);

        TotalDebug.INSTANCE.network.registerMessage(TickTimeResultMessage.class, TickTimeResultMessage.class, id++, Side.CLIENT);
        TotalDebug.INSTANCE.network.registerMessage(TickTimeRequestMessage.class, TickTimeRequestMessage.class, id++, Side.SERVER);

        TotalDebug.INSTANCE.network.registerMessage(ReceiveDataStateMessage.class, ReceiveDataStateMessage.class, id++, Side.SERVER);
        TotalDebug.INSTANCE.network.registerMessage(ChunkGridRequestInfoUpdateMessage.class, ChunkGridRequestInfoUpdateMessage.class, id++, Side.SERVER);

        TotalDebug.INSTANCE.network.registerMessage(RunScriptOnServerMessage.class, RunScriptOnServerMessage.class, id++, Side.SERVER);
        TotalDebug.INSTANCE.network.registerMessage(CompanionAppForwardedMessage.class, CompanionAppForwardedMessage.class, id++, Side.CLIENT);
        TotalDebug.INSTANCE.network.registerMessage(StopScriptOnServerMessage.class, StopScriptOnServerMessage.class, id++, Side.SERVER);
    }

    public void init(FMLInitializationEvent e) {
        MinecraftForge.EVENT_BUS.register(new Object() {
            @SubscribeEvent
            public void onTick(TickEvent.ServerTickEvent event) {
                if (event.phase != TickEvent.Phase.END)
                    return;

                getChunkGridManagerServer().update();
            }
        });
    }

    public ChunkGridManagerServer getChunkGridManagerServer() {
        return this.chunkGridManagerServer;
    }

    public ChunkGridManagerClient getChunkGridManagerClient() {
        return this.chunkGridManagerClient;
    }

    public DecompilationManager getDecompilationManager() {
        return null;
    }

    public TotalDebugClientConfig getClientConfig() {
        return null;
    }

    public CompanionApp getCompanionApp() {
        return null;
    }

    public IFMLSidedHandler getSidedHandler() {
        throw new UnsupportedOperationException();
    }

    public void addPreTickTask(Runnable task) {
        synchronized (this.preTickTasks) {
            this.preTickTasks.add(task);
        }
    }

    public void addPostTickTask(Runnable task) {
        synchronized (this.postTickTasks) {
            this.postTickTasks.add(task);
        }
    }
}
