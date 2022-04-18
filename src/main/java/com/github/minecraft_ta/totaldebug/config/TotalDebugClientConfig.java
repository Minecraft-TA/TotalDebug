package com.github.minecraft_ta.totaldebug.config;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.network.PacketBlockMessage;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.ConfigElement;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.fml.client.config.IConfigElement;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TotalDebugClientConfig {

    private static final String CATEGORY_DECOMPILATION = "decompilation";
    private static final String CATEGORY_VISUALS = "visuals";
    private static final String NETWORK = "network";

    public boolean useCompanionApp;
    public boolean renderBossBar;
    public List<String> blockedPacketClasses;
    private Configuration configuration;

    public TotalDebugClientConfig() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onConfigChange(ConfigChangedEvent.OnConfigChangedEvent event) {
        if (event.getModID().equals(TotalDebug.MOD_ID)) {
            load();
        }
    }

    public void load(Configuration configuration) {
        this.configuration = configuration;
        load();
    }

    private void load() {
        useCompanionApp = configuration.getBoolean("useCompanionApp", CATEGORY_DECOMPILATION, true, "Whether or not to open decompiled files in the TotalDebug companion app");
        renderBossBar = configuration.getBoolean("renderBossBar", CATEGORY_VISUALS, true, "Whether or not to render the boss bar");
        blockedPacketClasses = new ArrayList<>(Arrays.asList(configuration.getStringList("blockedPacketClasses", NETWORK, new String[]{}, "List of packets that the client should not receive")));

        if (configuration.hasChanged()) {
            configuration.save();
            TotalDebug.INSTANCE.network.sendToServer(new PacketBlockMessage(TotalDebug.PROXY.getClientConfig().blockedPacketClasses));
        }
    }

    public List<IConfigElement> getConfigElements() {
        List<IConfigElement> list = new ArrayList<>();

        list.add(new ConfigElement(configuration.getCategory(CATEGORY_DECOMPILATION)));
        list.add(new ConfigElement(configuration.getCategory(CATEGORY_VISUALS)));
        list.add(new ConfigElement(configuration.getCategory(NETWORK)));

        return list;
    }

    public Configuration getConfig() {
        return configuration;
    }

    public void setBlockPacket(String readString) {
        if (blockedPacketClasses.contains(readString)) {
            blockedPacketClasses.remove(readString);
        } else {
            blockedPacketClasses.add(readString);
        }
        configuration.get(NETWORK, "blockedPacketClasses", new String[]{}).set(blockedPacketClasses.toArray(new String[0]));
        load();
    }
}
