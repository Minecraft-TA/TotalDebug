package com.github.tth05.codeviewer;

import com.github.tth05.codeviewer.proxy.CommonProxy;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerAboutToStartEvent;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import org.apache.logging.log4j.Logger;

@Mod(
        modid = CodeViewer.MOD_ID,
        name = CodeViewer.MOD_NAME,
        version = CodeViewer.VERSION
)
public class CodeViewer {

    public static final String MOD_ID = "code_viewer";
    public static final String MOD_NAME = "Code Viewer";
    public static final String VERSION = "1.0";

    @Mod.Instance(MOD_ID)
    public static CodeViewer INSTANCE;

    @SidedProxy(clientSide = "com.github.tth05.codeviewer.proxy.ClientProxy", serverSide = "com.github.tth05.codeviewer.proxy.ServerProxy")
    public static CommonProxy PROXY;

    public static Logger LOGGER;

    public final SimpleNetworkWrapper network = NetworkRegistry.INSTANCE.newSimpleChannel(MOD_ID);
    public final DecompilationManager decompilationManager = new DecompilationManager();

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        LOGGER = event.getModLog();
        PROXY.preInit(event);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        PROXY.init(event);
    }

    @Mod.EventHandler
    public void aboutToStart(FMLServerAboutToStartEvent event) {
        //TODO: start in new thread
        decompilationManager.downloadFernflower();
    }
}
