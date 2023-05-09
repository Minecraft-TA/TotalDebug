package com.github.minecraft_ta.totaldebug.gui.config;

import com.github.minecraft_ta.totaldebug.Tags;
import com.github.minecraft_ta.totaldebug.TotalDebug;
import cpw.mods.fml.client.config.GuiConfig;
import net.minecraft.client.gui.GuiScreen;

public class ModGuiConfig extends GuiConfig {
    public ModGuiConfig(GuiScreen parentScreen) {
        super(
                parentScreen,
                TotalDebug.INSTANCE.config.getClientConfigElements(),
                Tags.MODID,
                false,
                false,
                GuiConfig.getAbridgedConfigPath(TotalDebug.INSTANCE.config.getConfig().toString())
        );
    }
}
