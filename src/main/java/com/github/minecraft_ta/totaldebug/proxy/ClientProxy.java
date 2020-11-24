package com.github.minecraft_ta.totaldebug.proxy;

import com.github.minecraft_ta.totaldebug.KeyBindings;
import com.github.minecraft_ta.totaldebug.block.tile.TickBlockTile;
import com.github.minecraft_ta.totaldebug.handler.KeyInputHandler;
import com.github.minecraft_ta.totaldebug.handler.TabOverlayRenderHandler;
import com.github.minecraft_ta.totaldebug.render.TickBlockTileRenderer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;

public class ClientProxy extends CommonProxy {

    @Override
    public void init(FMLInitializationEvent e) {
        super.init(e);
        MinecraftForge.EVENT_BUS.register(new KeyInputHandler());
        MinecraftForge.EVENT_BUS.register(new TabOverlayRenderHandler());

        ClientRegistry.bindTileEntitySpecialRenderer(TickBlockTile.class, new TickBlockTileRenderer());

        KeyBindings.init();
    }
}
