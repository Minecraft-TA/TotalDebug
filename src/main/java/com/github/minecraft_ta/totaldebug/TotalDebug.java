package com.github.minecraft_ta.totaldebug;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(TotalDebug.MOD_ID)
public final class TotalDebug {
    public static final String MOD_ID = "total_debug";
    public static final Logger LOGGER = LogUtils.getLogger();

    public TotalDebug(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("Initializing TotalDebug");
    }
}
