package com.github.minecraft_ta.totaldebug.config;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.ConfigElement;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.fml.client.config.IConfigElement;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.ArrayList;
import java.util.List;

public class TotalDebugClientConfig {

    private static final String CATEGORY_DECOMPILATION = "decompilation";

    public boolean useCompanionApp;

    private Configuration configuration;

    public TotalDebugClientConfig() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onConfigChange(ConfigChangedEvent.OnConfigChangedEvent event) {
        if (event.getModID().equals(TotalDebug.MOD_ID)) {
            System.out.println(FMLCommonHandler.instance().getEffectiveSide());
            load();
        }
    }

    public void load(Configuration configuration) {
        this.configuration = configuration;
        load();
    }

    private void load() {
        useCompanionApp = configuration.getBoolean("useCompanionApp", CATEGORY_DECOMPILATION, true, "Whether or not to open decompiled files in the TotalDebug companion app");

        if (configuration.hasChanged())
            configuration.save();
    }

    public List<IConfigElement> getConfigElements() {
        List<IConfigElement> list = new ArrayList<>();

        list.add(new ConfigElement(configuration.getCategory(CATEGORY_DECOMPILATION)));

        return list;
    }

    public Configuration getConfig() {
        return configuration;
    }
}
