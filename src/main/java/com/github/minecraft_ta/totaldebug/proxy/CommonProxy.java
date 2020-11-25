package com.github.minecraft_ta.totaldebug.proxy;

import com.github.minecraft_ta.totaldebug.DecompilationManager;
import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.block.tile.TickBlockTile;
import com.github.minecraft_ta.totaldebug.network.*;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.registry.GameRegistry;
import net.minecraftforge.fml.relauncher.Side;

public class CommonProxy {

    public void preInit(FMLPreInitializationEvent e) {
        GameRegistry.registerTileEntity(TickBlockTile.class, new ResourceLocation(TotalDebug.MOD_ID, "tick_block_tile"));

        int id = 0;
        TotalDebug.INSTANCE.network.registerMessage(TicksPerSecondMessage.class, TicksPerSecondMessage.class, id++, Side.CLIENT);

        TotalDebug.INSTANCE.network.registerMessage(TickTimeResultMessage.class, TickTimeResultMessage.class, id++, Side.CLIENT);
        TotalDebug.INSTANCE.network.registerMessage(TickTimeRequestMessage.class, TickTimeRequestMessage.class, id++, Side.SERVER);
    }

    public void init(FMLInitializationEvent e) {

    }

    public DecompilationManager getDecompilationManager() {
        return null;
    }
}
