package com.github.minecraft_ta.totalDebugCompanion.bytecode.insight;

/** Counts displayed next to one resolved declaration in the source editor. */
public record SymbolInsight(long usageCount, int implementationCount, int baseCount) {
    public static final SymbolInsight EMPTY = new SymbolInsight(0, 0, 0);

    public SymbolInsight {
        if (usageCount < 0 || implementationCount < 0 || baseCount < 0) {
            throw new IllegalArgumentException("Code insight counts must not be negative");
        }
    }
}
