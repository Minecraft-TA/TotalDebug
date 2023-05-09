package com.github.minecraft_ta.totaldebug.proxy;

import com.github.minecraft_ta.totaldebug.KeyBindings;
import com.github.minecraft_ta.totaldebug.block.tile.TickBlockTile;
import com.github.minecraft_ta.totaldebug.command.decompile.DecompileCommand;
import com.github.minecraft_ta.totaldebug.command.searchreference.SearchReferenceCommand;
import com.github.minecraft_ta.totaldebug.companionApp.CompanionApp;
import com.github.minecraft_ta.totaldebug.handler.BossBarHandler;
import com.github.minecraft_ta.totaldebug.handler.KeyInputHandler;
import com.github.minecraft_ta.totaldebug.handler.PacketLogger;
import com.github.minecraft_ta.totaldebug.handler.TabOverlayRenderHandler;
import com.github.minecraft_ta.totaldebug.render.TickBlockTileRenderer;
import com.github.minecraft_ta.totaldebug.util.decompiler.DecompilationManager;
import cpw.mods.fml.client.FMLClientHandler;
import cpw.mods.fml.client.registry.ClientRegistry;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.IFMLSidedHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.common.MinecraftForge;

import java.nio.file.Path;
import java.util.List;

public class ClientProxy extends CommonProxy {

    private final DecompilationManager decompilationManager = new DecompilationManager();
    private PacketLogger packetLogger;
    private CompanionApp companionApp;

    @Override
    public void preInit(FMLPreInitializationEvent e) {
        ClientRegistry.bindTileEntitySpecialRenderer(TickBlockTile.class, new TickBlockTileRenderer());
        super.preInit(e);
    }

    @Override
    public void init(FMLInitializationEvent e) {
        super.init(e);
        this.decompilationManager.setup();
        this.companionApp = new CompanionApp(this.decompilationManager.getDataDir().resolve(CompanionApp.COMPANION_APP_FOLDER));
        this.packetLogger = new PacketLogger();

        KeyInputHandler keyInputHandler = new KeyInputHandler();
        FMLCommonHandler.instance().bus().register(keyInputHandler);
        MinecraftForge.EVENT_BUS.register(keyInputHandler);
        MinecraftForge.EVENT_BUS.register(new TabOverlayRenderHandler());
        MinecraftForge.EVENT_BUS.register(new BossBarHandler());
        FMLCommonHandler.instance().bus().register(new GlobalTickHandler());

        ClientRegistry.bindTileEntitySpecialRenderer(TickBlockTile.class, new TickBlockTileRenderer());

        ClientCommandHandler.instance.registerCommand(new DecompileCommand());
        ClientCommandHandler.instance.registerCommand(new SearchReferenceCommand());

        KeyBindings.init();
    }

    @Override
    public DecompilationManager getDecompilationManager() {
        return this.decompilationManager;
    }

    @Override
    public CompanionApp getCompanionApp() {
        return this.companionApp;
    }

    @Override
    public PacketLogger getPackerLogger() {
        return this.packetLogger;
    }

    @Override
    public Path getMinecraftClassDumpPath() {
        return this.decompilationManager.getDataDir().resolve(CompanionApp.DATA_FOLDER).resolve("minecraft-class-dump.jar").toAbsolutePath().normalize();
    }

    @Override
    public IFMLSidedHandler getSidedHandler() {
        return FMLClientHandler.instance();
    }

    public class GlobalTickHandler {

        @SubscribeEvent
        public void onClientTick(TickEvent.ClientTickEvent event) {
            List<Runnable> tasks;
            if (event.phase == TickEvent.Phase.START)
                tasks = ClientProxy.super.preTickTasks;
            else
                tasks = ClientProxy.super.postTickTasks;

            synchronized (tasks) {
                tasks.forEach(Runnable::run);
                tasks.clear();
            }

            if (event.phase != TickEvent.Phase.END)
                return;

            getChunkGridManagerClient().update();
            getPackerLogger().update();
        }
    }
}
