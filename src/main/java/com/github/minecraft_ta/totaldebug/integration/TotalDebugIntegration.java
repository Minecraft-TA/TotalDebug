package com.github.minecraft_ta.totaldebug.integration;

import cpw.mods.fml.common.Loader;

public interface TotalDebugIntegration {

    default boolean isEnabled() {
        return Loader.isModLoaded(getName());
    }

    String getName();
}
