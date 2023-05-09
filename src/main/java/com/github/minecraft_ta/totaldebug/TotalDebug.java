package com.github.minecraft_ta.totaldebug;

import com.github.minecraft_ta.totaldebug.block.TickBlock;
import com.github.minecraft_ta.totaldebug.companionApp.script.ScriptRunner;
import com.github.minecraft_ta.totaldebug.config.TotalDebugConfig;
import com.github.minecraft_ta.totaldebug.proxy.CommonProxy;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStoppingEvent;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.common.registry.GameRegistry;
import net.minecraft.item.ItemBlock;
import net.minecraftforge.common.config.Configuration;
import org.apache.logging.log4j.Logger;

@Mod(
        modid = Tags.MODID,
        name = Tags.MODNAME,
        version = Tags.VERSION,
        guiFactory = "com.github.minecraft_ta.totaldebug.gui.config.ModGuiFactory"
)
public class TotalDebug {

    @Mod.Instance(Tags.MODID)
    public static TotalDebug INSTANCE;

    @SidedProxy(clientSide = "com.github.minecraft_ta.totaldebug.proxy.ClientProxy", serverSide = "com.github.minecraft_ta.totaldebug.proxy.ServerProxy")
    public static CommonProxy PROXY;

    public static Logger LOGGER;

    public final SimpleNetworkWrapper network = NetworkRegistry.INSTANCE.newSimpleChannel(Tags.MODID);
    public final TotalDebugConfig config = new TotalDebugConfig();

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        this.config.init(new Configuration(event.getSuggestedConfigurationFile()));
        LOGGER = event.getModLog();
        PROXY.preInit(event);
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        PROXY.init(event);
    }

    @Mod.EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        ScriptRunner.stopAllScripts();
    }

    @GameRegistry.ObjectHolder(Tags.MODID)
    public static class Blocks {

        public static final TickBlock TICK_BLOCK = null;
    }

    @GameRegistry.ObjectHolder(Tags.MODID)
    public static class Items {

        public static final ItemBlock TICK_BLOCK_ITEM = null;
    }

}
