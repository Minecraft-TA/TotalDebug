package com.github.minecraft_ta.totalDebugCompanion.decompiler.naming;

import org.jetbrains.java.decompiler.api.plugin.Plugin;
import com.github.minecraft_ta.totalDebugCompanion.decompiler.SourceSymbolCapture;
import org.jetbrains.java.decompiler.main.extern.IVariableNamingFactory;
import org.jetbrains.java.decompiler.main.extern.TextTokenVisitor;

public final class SelectiveVariableNamingPlugin implements Plugin {
    @Override
    public String id() {
        return "TotalDebugVariableNaming";
    }

    @Override
    public String description() {
        return "Shares parameter names with completion and gives unnamed Minecraft locals JAD-style names.";
    }

    @Override
    public void initialize() {
        TextTokenVisitor.addVisitor(VariableNameCapture::textTokenVisitor);
        SourceSymbolCapture.registerVisitor();
    }

    @Override
    public IVariableNamingFactory getRenamingFactory() {
        return SelectiveVariableNameProvider::new;
    }
}
