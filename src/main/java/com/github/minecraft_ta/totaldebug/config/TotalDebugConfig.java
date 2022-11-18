package com.github.minecraft_ta.totaldebug.config;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.network.PacketBlockMessage;
import cpw.mods.fml.client.config.IConfigElement;
import cpw.mods.fml.client.event.ConfigChangedEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.ConfigElement;
import net.minecraftforge.common.config.Configuration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class TotalDebugConfig {

    private static final String CATEGORY_SERVER = "server";
    private static final String CATEGORY_SCRIPTS = CATEGORY_SERVER + Configuration.CATEGORY_SPLITTER + "scripts";

    private static final String CATEGORY_CLIENT = "client";
    private static final String CATEGORY_DECOMPILATION = CATEGORY_CLIENT + Configuration.CATEGORY_SPLITTER + "decompilation";
    private static final String CATEGORY_VISUALS = CATEGORY_CLIENT + Configuration.CATEGORY_SPLITTER + "visuals";
    private static final String CATEGORY_NETWORK = CATEGORY_CLIENT + Configuration.CATEGORY_SPLITTER + "network";

    public boolean enableScripts;
    public boolean enableScriptsOnlyForOp;

    public boolean useCompanionApp;
    public boolean renderBossBar;
    public List<String> blockedPacketClasses;
    private Configuration configuration;

    public TotalDebugConfig() {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onConfigChange(ConfigChangedEvent.OnConfigChangedEvent event) {
        if (event.modID.equals(TotalDebug.MOD_ID)) {
            load();
            syncBlockedPackets();
        }
    }

    public void init(Configuration configuration) {
        this.configuration = configuration;
        load();
    }

    private void load() {
        enableScripts = configuration.getBoolean("enableScripts", CATEGORY_SCRIPTS, false, "Whether any scripts are allowed to be executed on the server");
        enableScriptsOnlyForOp = configuration.getBoolean("enableScriptsOnlyForOp", CATEGORY_SCRIPTS, true, "Whether only players with OP permissions are allowed to execute scripts on the server");

        useCompanionApp = configuration.getBoolean("useCompanionApp", CATEGORY_DECOMPILATION, true, "Whether or not to open decompiled files in the TotalDebug companion app");
        renderBossBar = configuration.getBoolean("renderBossBar", CATEGORY_VISUALS, true, "Whether or not to render the boss bar");
        blockedPacketClasses = new ArrayList<>(Arrays.asList(configuration.getStringList("blockedPacketClasses", CATEGORY_NETWORK, new String[]{}, "List of packets that the client should not receive")));

        if (configuration.hasChanged())
            configuration.save();
    }

    public void syncBlockedPackets() {
        TotalDebug.INSTANCE.network.sendToServer(new PacketBlockMessage(this.blockedPacketClasses));
    }

    public void toggleBlockedPacket(String className) {
        if (blockedPacketClasses.contains(className)) {
            blockedPacketClasses.remove(className);
        } else {
            blockedPacketClasses.add(className);
        }

        configuration.get(CATEGORY_NETWORK, "blockedPacketClasses", new String[]{}).set(blockedPacketClasses.toArray(new String[0]));
        load();
        syncBlockedPackets();
    }

    public List<IConfigElement> getClientConfigElements() {
        List<IConfigElement> list = new ArrayList<>();

        list.add(new ConfigElement(configuration.getCategory(CATEGORY_CLIENT)));
        return list;
    }

    public Configuration getConfig() {
        return configuration;
    }
}
