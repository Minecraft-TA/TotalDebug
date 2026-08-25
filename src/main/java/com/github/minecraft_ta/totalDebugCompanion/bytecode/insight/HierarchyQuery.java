package com.github.minecraft_ta.totalDebugCompanion.bytecode.insight;

import com.github.minecraft_ta.totalDebugCompanion.jdt.symbol.CodeSymbol;

import java.util.Objects;

/** One exact class or method hierarchy lookup. */
public record HierarchyQuery(CodeSymbol symbol, HierarchyDirection direction, boolean directSubtypesOnly) {
    public HierarchyQuery {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(direction, "direction");
        if (symbol instanceof CodeSymbol.FieldSymbol) {
            throw new IllegalArgumentException("Fields do not have a type hierarchy");
        }
        if (direction == HierarchyDirection.BASE_METHODS
                && !(symbol instanceof CodeSymbol.MethodSymbol)) {
            throw new IllegalArgumentException("Only methods have base declarations");
        }
        if (directSubtypesOnly && !(symbol instanceof CodeSymbol.ClassSymbol)) {
            throw new IllegalArgumentException("Direct-subtype filtering only applies to classes");
        }
    }

    public static HierarchyQuery implementations(CodeSymbol symbol) {
        return new HierarchyQuery(symbol, HierarchyDirection.IMPLEMENTATIONS, false);
    }

    public static HierarchyQuery implementations(CodeSymbol.ClassSymbol symbol, boolean directOnly) {
        return new HierarchyQuery(symbol, HierarchyDirection.IMPLEMENTATIONS, directOnly);
    }

    public static HierarchyQuery baseMethods(CodeSymbol.MethodSymbol symbol) {
        return new HierarchyQuery(symbol, HierarchyDirection.BASE_METHODS, false);
    }
}
