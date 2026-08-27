package com.github.minecraft_ta.totalDebugCompanion.debugger;

import java.util.Objects;

/** A debugger expression name that is valid in the represented frame or source scope. */
public record ExpressionSuggestion(String text, String detail, Kind kind) {
    public ExpressionSuggestion {
        if (Objects.requireNonNull(text, "text").isBlank()) {
            throw new IllegalArgumentException("text must not be blank");
        }
        detail = Objects.requireNonNullElse(detail, "");
        Objects.requireNonNull(kind, "kind");
    }

    public enum Kind {
        VARIABLE,
        FIELD,
        KEYWORD
    }
}
