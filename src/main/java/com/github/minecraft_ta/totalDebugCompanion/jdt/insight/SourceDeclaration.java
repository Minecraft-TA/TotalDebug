package com.github.minecraft_ta.totalDebugCompanion.jdt.insight;

import com.github.minecraft_ta.totalDebugCompanion.jdt.symbol.CodeSymbol;

import java.util.Objects;

/** A rendered source declaration that can carry code-vision text and a gutter marker. */
public record SourceDeclaration(CodeSymbol symbol, int anchorOffset, int markerOffset, int endOffset) {
    public SourceDeclaration {
        Objects.requireNonNull(symbol, "symbol");
        if (anchorOffset < 0 || markerOffset < 0 || endOffset < markerOffset) {
            throw new IllegalArgumentException("Source declaration offsets must not be negative");
        }
    }
}
