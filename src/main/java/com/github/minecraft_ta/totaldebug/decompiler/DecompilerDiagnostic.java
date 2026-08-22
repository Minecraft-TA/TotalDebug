package com.github.minecraft_ta.totaldebug.decompiler;

import java.util.Objects;

public record DecompilerDiagnostic(
        Severity severity,
        String message,
        String exceptionType,
        String exceptionMessage
) {
    public DecompilerDiagnostic {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(message, "message");
    }

    public enum Severity {
        WARNING,
        ERROR
    }
}
