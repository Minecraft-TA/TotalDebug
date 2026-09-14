package com.github.minecraft_ta.totalDebugCompanion.decompiler;

import com.github.minecraft_ta.totalDebugCompanion.source.SourceLineMap;
import com.github.minecraft_ta.totalDebugCompanion.source.SourceVariableNames;

import java.util.List;
import com.github.minecraft_ta.totalDebugCompanion.source.SourceDocument;
import java.util.Objects;

public record DecompilationResult(
        String source,
        Status status,
        List<DecompilerDiagnostic> diagnostics,
        SourceLineMap lineMap,
        SourceVariableNames variableNames,
        List<SourceDocument.SymbolSpan> symbols
) {
    public DecompilationResult {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(status, "status");
        diagnostics = List.copyOf(diagnostics);
        Objects.requireNonNull(lineMap, "lineMap");
        Objects.requireNonNull(variableNames, "variableNames");
        symbols = List.copyOf(symbols);
        if (source.isBlank()) {
            throw new IllegalArgumentException("Decompiled source must not be blank");
        }
    }

    public DecompilationResult(
            String source,
            Status status,
            List<DecompilerDiagnostic> diagnostics
    ) {
        this(source, status, diagnostics, SourceLineMap.empty(), SourceVariableNames.empty(), List.of());
    }

    public boolean isComplete() {
        return this.status == Status.COMPLETE;
    }

    public enum Status {
        COMPLETE,
        PARTIAL
    }
}
