package com.github.minecraft_ta.totaldebug.proxy;

import com.github.minecraft_ta.totaldebug.DecompilationManager;
import com.github.minecraft_ta.totaldebug.KeyBindings;
import com.github.minecraft_ta.totaldebug.block.tile.TickBlockTile;
import com.github.minecraft_ta.totaldebug.command.decompile.DecompileCommand;
import com.github.minecraft_ta.totaldebug.command.searchreference.SearchReferenceCommand;
import com.github.minecraft_ta.totaldebug.handler.KeyInputHandler;
import com.github.minecraft_ta.totaldebug.handler.TabOverlayRenderHandler;
import com.github.minecraft_ta.totaldebug.render.TickBlockTileRenderer;
import com.github.minecraft_ta.totaldebug.util.mappings.RemappingUtil;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;

public class ClientProxy extends CommonProxy {

    private final DecompilationManager decompilationManager = new DecompilationManager();

    @Override
    public void init(FMLInitializationEvent e) {
        super.init(e);
        decompilationManager.setup();
        RemappingUtil.loadMappings();

        MinecraftForge.EVENT_BUS.register(new KeyInputHandler());
        MinecraftForge.EVENT_BUS.register(new TabOverlayRenderHandler());

        ClientRegistry.bindTileEntitySpecialRenderer(TickBlockTile.class, new TickBlockTileRenderer());

        ClientCommandHandler.instance.registerCommand(new DecompileCommand());
        ClientCommandHandler.instance.registerCommand(new SearchReferenceCommand());

        KeyBindings.init();
    }

    @Override
    public DecompilationManager getDecompilationManager() {
        return this.decompilationManager;
    }
}
