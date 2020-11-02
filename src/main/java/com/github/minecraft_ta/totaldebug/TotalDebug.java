package com.github.minecraft_ta.totaldebug;

import com.github.minecraft_ta.totaldebug.proxy.CommonProxy;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerAboutToStartEvent;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import org.apache.logging.log4j.Logger;

@Mod(
        modid = TotalDebug.MOD_ID,
        name = TotalDebug.MOD_NAME,
        version = TotalDebug.VERSION
)
public class TotalDebug {

    public static final String MOD_ID = "total_debug";
    public static final String MOD_NAME = "Total Debug";
    public static final String VERSION = "1.0";

    @Mod.Instance(MOD_ID)
    public static TotalDebug INSTANCE;

    @SidedProxy(clientSide = "com.github.minecraft_ta.totaldebug.proxy.ClientProxy", serverSide = "com.github.minecraft_ta.totaldebug.proxy.ServerProxy")
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
