package com.github.minecraft_ta.totaldebug.script;

/** Retains a bounded prefix while still counting all text written by a live script. */
final class ExecutionTextBuffer {
    private final int retainedLimit;
    private final StringBuilder retained = new StringBuilder();
    private int totalCharacters;

    ExecutionTextBuffer(int retainedLimit) {
        if (retainedLimit < 0) {
            throw new IllegalArgumentException("retainedLimit must not be negative");
        }
        this.retainedLimit = retainedLimit;
    }

    void append(Object value) {
        String text = String.valueOf(value);
        this.totalCharacters = saturatedAdd(this.totalCharacters, text.length());
        appendRetained(text);
    }

    void append(ExecutionText value) {
        this.totalCharacters = saturatedAdd(this.totalCharacters, value.totalCharacters());
        appendRetained(value.text());
    }

    private void appendRetained(String text) {
        int remaining = this.retainedLimit - this.retained.length();
        if (remaining <= 0 || text.isEmpty()) {
            return;
        }
        int end = Math.min(text.length(), remaining);
        if (end > 0 && end < text.length()
                && Character.isHighSurrogate(text.charAt(end - 1))
                && Character.isLowSurrogate(text.charAt(end))) {
            end--;
        }
        this.retained.append(text, 0, end);
    }

    ExecutionText snapshot() {
        int retainedCharacters = this.retained.length();
        if (retainedCharacters < this.totalCharacters && retainedCharacters > 0
                && Character.isHighSurrogate(this.retained.charAt(retainedCharacters - 1))) {
            retainedCharacters--;
        }
        return new ExecutionText(
                this.retained.substring(0, retainedCharacters),
                this.totalCharacters,
                retainedCharacters < this.totalCharacters
        );
    }

    boolean isFull() {
        return this.retained.length() >= this.retainedLimit;
    }

    private static int saturatedAdd(int left, int right) {
        return Integer.MAX_VALUE - left < right ? Integer.MAX_VALUE : left + right;
    }
}
