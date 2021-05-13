package com.github.minecraft_ta.totaldebug.gui.config;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.fml.client.config.GuiConfig;

public class ModGuiConfig extends GuiConfig {
    public ModGuiConfig(GuiScreen parentScreen) {
        super(
                parentScreen,
                TotalDebug.PROXY.getClientConfig().getConfigElements(),
                TotalDebug.MOD_ID,
                false,
                false,
                GuiConfig.getAbridgedConfigPath(TotalDebug.PROXY.getClientConfig().getConfig().toString())
        );
    }
}
