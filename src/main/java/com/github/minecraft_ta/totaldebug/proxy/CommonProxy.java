package com.github.minecraft_ta.totaldebug.proxy;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.network.*;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.relauncher.Side;

public class CommonProxy {

    public void preInit(FMLPreInitializationEvent e) {
        int id = 0;
        TotalDebug.INSTANCE.network.registerMessage(TicksPerSecondMessage.class, TicksPerSecondMessage.class, id++, Side.CLIENT);

        TotalDebug.INSTANCE.network.registerMessage(DecompilationResultMessage.class, DecompilationResultMessage.class, id++, Side.CLIENT);
        TotalDebug.INSTANCE.network.registerMessage(DecompilationRequestMessage.class, DecompilationRequestMessage.class, id++, Side.SERVER);
        TotalDebug.INSTANCE.network.registerMessage(LoadedResultMessage.class, LoadedResultMessage.class, id++, Side.CLIENT);
        TotalDebug.INSTANCE.network.registerMessage(LoadedRequestMessage.class, LoadedRequestMessage.class, id++, Side.SERVER);
    }

    public void init(FMLInitializationEvent e) {

    }
}
