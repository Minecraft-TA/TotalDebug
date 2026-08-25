package com.github.minecraft_ta.totalDebugCompanion.ui.components.editors;

import com.github.minecraft_ta.totalDebugCompanion.bytecode.insight.SymbolInsight;
import com.github.minecraft_ta.totalDebugCompanion.jdt.insight.SourceDeclaration;

import java.util.Objects;

record CodeVisionEntry(SourceDeclaration declaration, SymbolInsight insight) {
    CodeVisionEntry {
        Objects.requireNonNull(declaration, "declaration");
        Objects.requireNonNull(insight, "insight");
    }
}
