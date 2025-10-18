package com.github.minecraft_ta.neoforge;

import net.neoforged.fml.common.Mod;

import com.github.minecraft_ta.TotalDebug;

@Mod(TotalDebug.MOD_ID)
public final class TotalDebugNeoForge {
    public TotalDebugNeoForge() {
        // Run our common setup.
        TotalDebug.init();
    }
}
