package com.github.tth05.codeviewer.proxy;

import com.github.tth05.codeviewer.CodeViewer;
import com.github.tth05.codeviewer.handler.PlayerInteractionHandler;
import com.github.tth05.codeviewer.network.DecompilationResultMessage;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.relauncher.Side;

public class CommonProxy {

    public void preInit(FMLPreInitializationEvent e) {
        MinecraftForge.EVENT_BUS.register(new PlayerInteractionHandler());

        int id = 0;
        CodeViewer.INSTANCE.network.registerMessage(DecompilationResultMessage.class, DecompilationResultMessage.class, id++, Side.CLIENT);
    }

    public void init(FMLInitializationEvent e) {

    }
}
