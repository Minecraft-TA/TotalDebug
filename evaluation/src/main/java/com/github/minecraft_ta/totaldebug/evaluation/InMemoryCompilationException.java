package com.github.minecraft_ta.totaldebug.evaluation;

import java.util.List;
import java.util.stream.Collectors;

public final class InMemoryCompilationException extends Exception {
    private final List<CompilationDiagnostic> diagnostics;

    public InMemoryCompilationException(List<CompilationDiagnostic> diagnostics) {
        super(diagnostics.stream().map(CompilationDiagnostic::formatted).collect(Collectors.joining(System.lineSeparator())));
        this.diagnostics = List.copyOf(diagnostics);
    }

    public InMemoryCompilationException(String message) {
        super(message);
        this.diagnostics = List.of();
    }

    public InMemoryCompilationException(String message, Throwable cause) {
        super(message, cause);
        this.diagnostics = List.of();
    }

    public List<CompilationDiagnostic> diagnostics() { return diagnostics; }
}
