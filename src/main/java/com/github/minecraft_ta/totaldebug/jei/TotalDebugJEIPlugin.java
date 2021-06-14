package com.github.minecraft_ta.totaldebug.jei;

import mezz.jei.api.IJeiRuntime;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.IModRegistry;
import mezz.jei.api.JEIPlugin;

import javax.annotation.Nonnull;

@JEIPlugin
public class TotalDebugJEIPlugin implements IModPlugin {

    public static TotalDebugJEIPlugin INSTANCE;

    private IJeiRuntime runtime;

    @Override
    public void register(@Nonnull IModRegistry registry) {
        INSTANCE = this;
    }

    @Override
    public void onRuntimeAvailable(@Nonnull IJeiRuntime jeiRuntime) {
        this.runtime = jeiRuntime;
    }

    public IJeiRuntime getRuntime() {
        return runtime;
    }
}
