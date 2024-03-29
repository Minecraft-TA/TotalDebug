package com.github.minecraft_ta.totaldebug.proxy;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.block.TickBlock;
import com.github.minecraft_ta.totaldebug.block.tile.TickBlockTile;
import com.github.minecraft_ta.totaldebug.companionApp.CompanionApp;
import com.github.minecraft_ta.totaldebug.companionApp.chunkGrid.ChunkGridManagerClient;
import com.github.minecraft_ta.totaldebug.companionApp.chunkGrid.ChunkGridManagerServer;
import com.github.minecraft_ta.totaldebug.companionApp.script.ScriptRunner;
import com.github.minecraft_ta.totaldebug.handler.ChannelInputHandler;
import com.github.minecraft_ta.totaldebug.handler.PacketLogger;
import com.github.minecraft_ta.totaldebug.network.*;
import com.github.minecraft_ta.totaldebug.network.chunkGrid.ChunkGridRequestInfoUpdateMessage;
import com.github.minecraft_ta.totaldebug.network.chunkGrid.ReceiveDataStateMessage;
import com.github.minecraft_ta.totaldebug.network.script.RunScriptOnServerMessage;
import com.github.minecraft_ta.totaldebug.network.script.StopScriptOnServerMessage;
import com.github.minecraft_ta.totaldebug.util.decompiler.DecompilationManager;
import com.github.minecraft_ta.totaldebug.util.bytecode.RuntimeMappingsTransformer;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.IFMLSidedHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.eventhandler.EventBus;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.registry.GameRegistry;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class CommonProxy {

    private ChunkGridManagerServer chunkGridManagerServer;
    private ChunkGridManagerClient chunkGridManagerClient;

    protected final List<Runnable> preTickTasks = new ArrayList<>();
    protected final List<Runnable> postTickTasks = new ArrayList<>();

    public void preInit(FMLPreInitializationEvent e) {
        GameRegistry.registerBlock(new TickBlock(), "tick_block");
        GameRegistry.registerTileEntity(TickBlockTile.class, "tick_block_tile");

        int id = 0;
        TotalDebug.INSTANCE.network.registerMessage(TicksPerSecondMessage.class, TicksPerSecondMessage.class, id++, Side.CLIENT);

        TotalDebug.INSTANCE.network.registerMessage(TickTimeResultMessage.class, TickTimeResultMessage.class, id++, Side.CLIENT);
        TotalDebug.INSTANCE.network.registerMessage(TickTimeRequestMessage.class, TickTimeRequestMessage.class, id++, Side.SERVER);

        TotalDebug.INSTANCE.network.registerMessage(ReceiveDataStateMessage.class, ReceiveDataStateMessage.class, id++, Side.SERVER);
        TotalDebug.INSTANCE.network.registerMessage(ChunkGridRequestInfoUpdateMessage.class, ChunkGridRequestInfoUpdateMessage.class, id++, Side.SERVER);

        TotalDebug.INSTANCE.network.registerMessage(RunScriptOnServerMessage.class, RunScriptOnServerMessage.class, id++, Side.SERVER);
        TotalDebug.INSTANCE.network.registerMessage(CompanionAppForwardedMessage.class, CompanionAppForwardedMessage.class, id++, Side.CLIENT);
        TotalDebug.INSTANCE.network.registerMessage(StopScriptOnServerMessage.class, StopScriptOnServerMessage.class, id++, Side.SERVER);

        TotalDebug.INSTANCE.network.registerMessage(PacketBlockMessage.class, PacketBlockMessage.class, id++, Side.SERVER);
    }

    public void init(FMLInitializationEvent e) {
        RuntimeMappingsTransformer.loadMappings();

        EventBus eventBus = FMLCommonHandler.instance().bus();
        eventBus.register(new ChannelInputHandler());
        eventBus.register(new GlobalTickHandler());
        eventBus.register(new PlayerLeaveHandler());
    }

    public ChunkGridManagerServer getChunkGridManagerServer() {
        if (this.chunkGridManagerServer == null)
            this.chunkGridManagerServer = new ChunkGridManagerServer();
        return this.chunkGridManagerServer;
    }

    @SideOnly(Side.CLIENT)
    public ChunkGridManagerClient getChunkGridManagerClient() {
        if (this.chunkGridManagerClient == null)
            this.chunkGridManagerClient = new ChunkGridManagerClient();
        return this.chunkGridManagerClient;
    }

    public DecompilationManager getDecompilationManager() {
        return null;
    }

    public CompanionApp getCompanionApp() {
        return null;
    }

    public PacketLogger getPackerLogger() {
        return null;
    }

    public Path getMinecraftClassDumpPath() {
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

    public static class PlayerLeaveHandler {
        @SubscribeEvent
        public void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
            ScriptRunner.stopAllScripts(event.player);
        }
    }

    public class GlobalTickHandler {

        @SubscribeEvent
        public void onTick(TickEvent.ServerTickEvent event) {
            if (event.phase != TickEvent.Phase.END)
                return;

            getChunkGridManagerServer().update();
        }
    }
}
