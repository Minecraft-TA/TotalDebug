package com.github.minecraft_ta.totalDebugCompanion.debugger;

import java.util.Objects;

/** The shared token and member-owner range used by all debugger completion clients. */
public record DebuggerCompletionRange(
        int start,
        int end,
        String prefix,
        boolean memberAccess,
        int ownerEnd
) {
    public DebuggerCompletionRange {
        Objects.requireNonNull(prefix, "prefix");
        if (start < 0 || end < start || ownerEnd < -1 || ownerEnd > start) {
            throw new IllegalArgumentException("Invalid debugger completion range");
        }
    }

    public static DebuggerCompletionRange around(String text, int caret) {
        Objects.requireNonNull(text, "text");
        if (caret < 0 || caret > text.length()) {
            throw new IllegalArgumentException("caret is outside the expression");
        }
        int start = caret;
        while (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) {
            start--;
        }
        int end = caret;
        while (end < text.length() && Character.isJavaIdentifierPart(text.charAt(end))) {
            end++;
        }
        int dot = start - 1;
        if (dot < 0 || text.charAt(dot) != '.') {
            return new DebuggerCompletionRange(start, end, text.substring(start, caret), false, -1);
        }
        int ownerEnd = dot;
        while (ownerEnd > 0 && Character.isWhitespace(text.charAt(ownerEnd - 1))) {
            ownerEnd--;
        }
        return new DebuggerCompletionRange(start, end, text.substring(start, caret), true, ownerEnd);
    }

    public static int offsetOf(String text, int line, int column) {
        Objects.requireNonNull(text, "text");
        if (line < 0 || column < 0) {
            throw new IllegalArgumentException("Completion position must not be negative");
        }
        int offset = 0;
        for (int current = 0; current < line; current++) {
            int newline = text.indexOf('\n', offset);
            if (newline < 0) {
                return text.length();
            }
            offset = newline + 1;
        }
        return Math.min(text.length(), offset + column);
    }
}
