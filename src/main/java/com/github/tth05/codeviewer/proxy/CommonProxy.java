package com.github.tth05.codeviewer.proxy;

import com.github.tth05.codeviewer.CodeViewer;
import com.github.tth05.codeviewer.handler.KeyInputHandler;
import com.github.tth05.codeviewer.network.DecompilationRequestMessage;
import com.github.tth05.codeviewer.network.DecompilationResultMessage;
import com.github.tth05.codeviewer.network.LoadedRequestMessage;
import com.github.tth05.codeviewer.network.LoadedResultMessage;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.relauncher.Side;

public class CommonProxy {

    public void preInit(FMLPreInitializationEvent e) {
        MinecraftForge.EVENT_BUS.register(new KeyInputHandler());

        int id = 0;
        CodeViewer.INSTANCE.network.registerMessage(DecompilationResultMessage.class, DecompilationResultMessage.class, id++, Side.CLIENT);
        CodeViewer.INSTANCE.network.registerMessage(DecompilationRequestMessage.class, DecompilationRequestMessage.class, id++, Side.SERVER);
        CodeViewer.INSTANCE.network.registerMessage(LoadedResultMessage.class, LoadedResultMessage.class, id++, Side.CLIENT);
        CodeViewer.INSTANCE.network.registerMessage(LoadedRequestMessage.class, LoadedRequestMessage.class, id++, Side.SERVER);
    }

    public void init(FMLInitializationEvent e) {

    }
}
