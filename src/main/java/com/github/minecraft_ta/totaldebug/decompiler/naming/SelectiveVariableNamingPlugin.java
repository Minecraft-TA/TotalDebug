package com.github.minecraft_ta.totaldebug.decompiler.naming;

import org.jetbrains.java.decompiler.api.plugin.Plugin;
import org.jetbrains.java.decompiler.main.extern.IVariableNamingFactory;

public final class SelectiveVariableNamingPlugin implements Plugin {
    @Override
    public String id() {
        return "TotalDebugVariableNaming";
    }

    @Override
    public String description() {
        return "Applies Parchment parameter names and JAD-style names to unnamed Minecraft variables.";
    }

    @Override
    public IVariableNamingFactory getRenamingFactory() {
        return SelectiveVariableNameProvider::new;
    }
}
