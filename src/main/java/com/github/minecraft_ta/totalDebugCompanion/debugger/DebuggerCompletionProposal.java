package com.github.minecraft_ta.totalDebugCompanion.debugger;

import java.util.Objects;

/** A typed proposal shared by runtime and source-backed debugger completion. */
public record DebuggerCompletionProposal(
        String label,
        String insertionText,
        Kind kind,
        String detail,
        int replacementStart,
        int replacementEnd,
        int caretOffset,
        int rank
) {
    public DebuggerCompletionProposal {
        if (Objects.requireNonNull(label, "label").isBlank()) {
            throw new IllegalArgumentException("label must not be blank");
        }
        if (Objects.requireNonNull(insertionText, "insertionText").isBlank()) {
            throw new IllegalArgumentException("insertionText must not be blank");
        }
        Objects.requireNonNull(kind, "kind");
        detail = Objects.requireNonNullElse(detail, "");
        if (replacementStart < 0 || replacementEnd < replacementStart) {
            throw new IllegalArgumentException("Invalid completion replacement range");
        }
        if (caretOffset < 0 || caretOffset > insertionText.length()) {
            throw new IllegalArgumentException("Caret offset is outside insertion text");
        }
    }

    public DebuggerCompletionProposal(
            String label,
            String insertionText,
            Kind kind,
            String detail,
            int replacementStart,
            int replacementEnd
    ) {
        this(label, insertionText, kind, detail, replacementStart, replacementEnd, insertionText.length(), 0);
    }

    public DebuggerCompletionProposal withRange(int start, int end) {
        return new DebuggerCompletionProposal(
                this.label, this.insertionText, this.kind, this.detail,
                start, end, this.caretOffset, this.rank
        );
    }

    public DebuggerCompletionProposal withRank(int newRank) {
        return new DebuggerCompletionProposal(
                this.label, this.insertionText, this.kind, this.detail,
                this.replacementStart, this.replacementEnd, this.caretOffset, newRank
        );
    }

    public enum Kind {
        VARIABLE,
        FIELD,
        METHOD,
        TYPE,
        CONSTANT,
        KEYWORD
    }
}
