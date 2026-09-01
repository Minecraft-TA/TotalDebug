package com.github.minecraft_ta.totaldebug.script;

import java.util.Objects;

/** Retained text plus explicit completeness metadata. */
public record ExecutionText(String text, int totalCharacters, boolean truncated) {
    public ExecutionText {
        text = Objects.requireNonNullElse(text, "");
        if (totalCharacters < text.length()) {
            throw new IllegalArgumentException("totalCharacters must cover the retained text");
        }
        if (truncated != (totalCharacters > text.length())) {
            throw new IllegalArgumentException("truncated must match the retained character count");
        }
    }

    public static ExecutionText empty() {
        return complete("");
    }

    public static ExecutionText complete(String text) {
        String value = Objects.requireNonNullElse(text, "");
        return new ExecutionText(value, value.length(), false);
    }

    public ExecutionText retain(int characters) {
        int retained = Math.max(0, Math.min(characters, this.text.length()));
        if (retained > 0 && retained < this.text.length()
                && Character.isHighSurrogate(this.text.charAt(retained - 1))
                && Character.isLowSurrogate(this.text.charAt(retained))) {
            retained--;
        }
        if (retained == this.text.length()) {
            return this;
        }
        return new ExecutionText(this.text.substring(0, retained), this.totalCharacters, true);
    }
}
