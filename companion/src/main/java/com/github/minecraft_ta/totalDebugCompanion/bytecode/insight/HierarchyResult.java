package com.github.minecraft_ta.totalDebugCompanion.bytecode.insight;

import com.github.minecraft_ta.totalDebugCompanion.jdt.symbol.CodeSymbol;

import java.util.Objects;

/** An immutable hierarchy declaration safe to hand from a JIndex worker to Swing. */
public record HierarchyResult(CodeSymbol symbol, int sourceId) implements Comparable<HierarchyResult> {
    public HierarchyResult {
        Objects.requireNonNull(symbol, "symbol");
        if (sourceId < 0) {
            throw new IllegalArgumentException("sourceId must not be negative");
        }
    }

    @Override
    public int compareTo(HierarchyResult other) {
        int classOrder = this.symbol.ownerClassName().compareTo(other.symbol.ownerClassName());
        return classOrder != 0 ? classOrder : this.symbol.displayName().compareTo(other.symbol.displayName());
    }
}
