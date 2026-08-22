package com.github.minecraft_ta.totaldebug.decompiler;

import java.util.List;
import java.util.Objects;

public record DecompilationResult(
        String source,
        Status status,
        List<DecompilerDiagnostic> diagnostics
) {
    public DecompilationResult {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(status, "status");
        diagnostics = List.copyOf(diagnostics);
        if (source.isBlank()) {
            throw new IllegalArgumentException("Decompiled source must not be blank");
        }
    }

    public boolean isComplete() {
        return this.status == Status.COMPLETE;
    }

    public enum Status {
        COMPLETE,
        PARTIAL
    }
}
