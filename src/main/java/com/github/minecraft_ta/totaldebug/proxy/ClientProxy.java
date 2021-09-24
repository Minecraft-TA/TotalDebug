package com.github.minecraft_ta.totaldebug.proxy;

import com.github.minecraft_ta.totaldebug.DecompilationManager;
import com.github.minecraft_ta.totaldebug.KeyBindings;
import com.github.minecraft_ta.totaldebug.block.tile.TickBlockTile;
import com.github.minecraft_ta.totaldebug.command.decompile.DecompileCommand;
import com.github.minecraft_ta.totaldebug.command.searchreference.SearchReferenceCommand;
import com.github.minecraft_ta.totaldebug.companionApp.CompanionApp;
import com.github.minecraft_ta.totaldebug.config.TotalDebugClientConfig;
import com.github.minecraft_ta.totaldebug.handler.KeyInputHandler;
import com.github.minecraft_ta.totaldebug.handler.TabOverlayRenderHandler;
import com.github.minecraft_ta.totaldebug.render.TickBlockTileRenderer;
import com.github.minecraft_ta.totaldebug.util.mappings.RemappingUtil;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.fml.client.FMLClientHandler;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.IFMLSidedHandler;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.List;

public class ClientProxy extends CommonProxy {

    private final DecompilationManager decompilationManager = new DecompilationManager();
    private final TotalDebugClientConfig clientConfig = new TotalDebugClientConfig();
    private CompanionApp companionApp;

    @Override
    public void preInit(FMLPreInitializationEvent e) {
        super.preInit(e);
        this.clientConfig.load(new Configuration(e.getSuggestedConfigurationFile()));
    }

    @Override
    public void init(FMLInitializationEvent e) {
        super.init(e);
        this.decompilationManager.setup();
        this.companionApp = new CompanionApp(this.decompilationManager.getDataDir().resolve(CompanionApp.COMPANION_APP_FOLDER));

        RemappingUtil.loadMappings();

        MinecraftForge.EVENT_BUS.register(new KeyInputHandler());
        MinecraftForge.EVENT_BUS.register(new TabOverlayRenderHandler());
        MinecraftForge.EVENT_BUS.register(new Object() {
            @SubscribeEvent
            public void onClientTick(TickEvent.ClientTickEvent event) {
                List<Runnable> tasks;
                if (event.phase == TickEvent.Phase.START)
                    tasks = ClientProxy.super.preTickTasks;
                else
                    tasks = ClientProxy.super.postTickTasks;

                tasks.forEach(Runnable::run);
                tasks.clear();

                if (event.phase != TickEvent.Phase.END)
                    return;

                getChunkGridManagerClient().update();
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
    public TotalDebugClientConfig getClientConfig() {
        return this.clientConfig;
    }

    @Override
    public CompanionApp getCompanionApp() {
        return this.companionApp;
    }

    @Override
    public IFMLSidedHandler getSidedHandler() {
        return FMLClientHandler.instance();
    }
}
