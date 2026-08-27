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

    public DebuggerCompletionProposal toProposal(int replacementStart, int replacementEnd) {
        return new DebuggerCompletionProposal(
                this.text,
                this.text,
                switch (this.kind) {
                    case VARIABLE -> DebuggerCompletionProposal.Kind.VARIABLE;
                    case FIELD -> DebuggerCompletionProposal.Kind.FIELD;
                    case KEYWORD -> DebuggerCompletionProposal.Kind.KEYWORD;
                },
                this.detail,
                replacementStart,
                replacementEnd
        );
    }

    public enum Kind {
        VARIABLE,
        FIELD,
        KEYWORD
    }
}
