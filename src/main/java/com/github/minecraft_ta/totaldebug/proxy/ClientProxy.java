package com.github.minecraft_ta.totaldebug.proxy;

import com.github.minecraft_ta.totaldebug.DecompilationManager;
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
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.FMLClientHandler;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.IFMLSidedHandler;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.nio.file.Path;
import java.util.List;

public class ClientProxy extends CommonProxy {

    private final DecompilationManager decompilationManager = new DecompilationManager();
    private PacketLogger packetLogger;
    private CompanionApp companionApp;

    @Override
    public void preInit(FMLPreInitializationEvent e) {
        super.preInit(e);
    }

    @Override
    public void init(FMLInitializationEvent e) {
        super.init(e);
        this.decompilationManager.setup();
        this.companionApp = new CompanionApp(this.decompilationManager.getDataDir().resolve(CompanionApp.COMPANION_APP_FOLDER));
        this.packetLogger = new PacketLogger();

        MinecraftForge.EVENT_BUS.register(new KeyInputHandler());
        MinecraftForge.EVENT_BUS.register(new TabOverlayRenderHandler());
        MinecraftForge.EVENT_BUS.register(new BossBarHandler());
        MinecraftForge.EVENT_BUS.register(new Object() {
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
        });

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
}
