package com.github.minecraft_ta.totalDebugCompanion.debugger;

import java.util.Objects;

/** The shared token and member-owner range used by all debugger completion clients. */
public record DebuggerCompletionRange(
        int start,
        int end,
        String prefix,
        boolean memberAccess,
        int ownerStart,
        int ownerEnd
) {
    public DebuggerCompletionRange {
        Objects.requireNonNull(prefix, "prefix");
        boolean invalidOwner = memberAccess
                ? ownerStart < 0 || ownerEnd < ownerStart || ownerEnd > start
                : ownerStart != -1 || ownerEnd != -1;
        if (start < 0 || end < start || invalidOwner) {
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
            return new DebuggerCompletionRange(start, end, text.substring(start, caret), false, -1, -1);
        }
        int ownerEnd = dot;
        while (ownerEnd > 0 && Character.isWhitespace(text.charAt(ownerEnd - 1))) {
            ownerEnd--;
        }
        return new DebuggerCompletionRange(
                start,
                end,
                text.substring(start, caret),
                true,
                findOwnerStart(text, ownerEnd),
                ownerEnd
        );
    }

    public String ownerExpression(String text) {
        Objects.requireNonNull(text, "text");
        if (!this.memberAccess || this.ownerEnd > text.length()) {
            throw new IllegalStateException("Completion range has no member owner in the supplied expression");
        }
        return text.substring(this.ownerStart, this.ownerEnd);
    }

    private static int findOwnerStart(String text, int ownerEnd) {
        int parentheses = 0;
        int brackets = 0;
        for (int index = ownerEnd - 1; index >= 0; index--) {
            char character = text.charAt(index);
            if (character == '\'' || character == '"') {
                index = quotedLiteralStart(text, index, character);
                continue;
            }
            switch (character) {
                case ')' -> parentheses++;
                case '(' -> {
                    if (parentheses == 0) {
                        return index + 1;
                    }
                    parentheses--;
                }
                case ']' -> brackets++;
                case '[' -> {
                    if (brackets == 0) {
                        return index + 1;
                    }
                    brackets--;
                }
                default -> {
                    if (parentheses == 0 && brackets == 0
                            && (Character.isWhitespace(character) || isExpressionBoundary(character))) {
                        return index + 1;
                    }
                }
            }
        }
        return 0;
    }

    private static int quotedLiteralStart(String text, int closingQuote, char quote) {
        for (int index = closingQuote - 1; index >= 0; index--) {
            if (text.charAt(index) == quote && !isEscaped(text, index)) {
                return index;
            }
        }
        return -1;
    }

    private static boolean isEscaped(String text, int index) {
        int backslashes = 0;
        for (int current = index - 1; current >= 0 && text.charAt(current) == '\\'; current--) {
            backslashes++;
        }
        return (backslashes & 1) != 0;
    }

    private static boolean isExpressionBoundary(char character) {
        return switch (character) {
            case '+', '-', '*', '/', '%', '&', '|', '^', '!', '~', '=', '<', '>', '?', ':', ',', ';', '{', '}' -> true;
            default -> false;
        };
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
