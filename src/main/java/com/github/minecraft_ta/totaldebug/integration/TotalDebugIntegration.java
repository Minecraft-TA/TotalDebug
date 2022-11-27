package com.github.minecraft_ta.totaldebug.integration;

import cpw.mods.fml.common.Loader;

public abstract class TotalDebugIntegration {

    private final boolean isEnabled;

    public TotalDebugIntegration() {
        this.isEnabled = Loader.isModLoaded(getName());
    }

    public boolean isEnabled() {
        return this.isEnabled;
    }

    protected abstract String getName();
}
