package com.github.minecraft_ta.totaldebug;

import com.github.minecraft_ta.totaldebug.block.TickBlock;
import com.github.minecraft_ta.totaldebug.companionApp.script.ScriptRunner;
import com.github.minecraft_ta.totaldebug.proxy.CommonProxy;
import net.minecraft.block.Block;
import net.minecraft.client.renderer.block.model.ModelResourceLocation;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.ModelRegistryEvent;
import net.minecraftforge.client.model.ModelLoader;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.common.registry.GameRegistry;
import org.apache.logging.log4j.Logger;

@Mod(
        modid = TotalDebug.MOD_ID,
        name = TotalDebug.MOD_NAME,
        version = TotalDebug.VERSION,
        guiFactory = "com.github.minecraft_ta.totaldebug.gui.config.ModGuiFactory"
)
public class TotalDebug {

    public static final String MOD_ID = "total_debug";
    public static final String MOD_NAME = "TotalDebug";
    public static final String VERSION = "1.5.0";

    @Mod.Instance(MOD_ID)
    public static TotalDebug INSTANCE;

    @SidedProxy(clientSide = "com.github.minecraft_ta.totaldebug.proxy.ClientProxy", serverSide = "com.github.minecraft_ta.totaldebug.proxy.ServerProxy")
    public static CommonProxy PROXY;

    public static Logger LOGGER;

    public final SimpleNetworkWrapper network = NetworkRegistry.INSTANCE.newSimpleChannel(MOD_ID);

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
    public void serverStopping(FMLServerStoppingEvent event) {
        ScriptRunner.stopAllScripts();
    }

    @GameRegistry.ObjectHolder(MOD_ID)
    public static class Blocks {
        public static final TickBlock TICK_BLOCK = null;
    }

    @GameRegistry.ObjectHolder(MOD_ID)
    public static class Items {
        public static final ItemBlock TICK_BLOCK_ITEM = null;
    }

    @Mod.EventBusSubscriber
    public static class ObjectRegistryHandler {

        @SubscribeEvent
        public static void addItems(RegistryEvent.Register<Item> event) {
            event.getRegistry().register(new ItemBlock(Blocks.TICK_BLOCK).setRegistryName(Blocks.TICK_BLOCK.getRegistryName()));
        }

        @SubscribeEvent
        public static void addBlocks(RegistryEvent.Register<Block> event) {
            event.getRegistry().register(new TickBlock().setRegistryName(new ResourceLocation(MOD_ID, "tick_block")).setTranslationKey("tick_block"));
        }

        @SubscribeEvent
        public static void registerRenders(ModelRegistryEvent event) {
            registerRender(Item.getItemFromBlock(Blocks.TICK_BLOCK));
        }

        public static void registerRender(Item item) {
            ModelLoader.setCustomModelResourceLocation(item, 0, new ModelResourceLocation(item.getRegistryName(), "inventory"));
        }
    }
}
