package com.github.minecraft_ta.totaldebug.integration;

import net.minecraftforge.fml.common.Loader;

public interface TotalDebugIntegration {

    default boolean isEnabled() {
        return Loader.isModLoaded(getName());
    }

    String getName();
}
