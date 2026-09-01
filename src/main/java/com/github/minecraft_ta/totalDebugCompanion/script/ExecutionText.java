package com.github.minecraft_ta.totalDebugCompanion.script;

import java.util.Objects;

/** Companion-side text value with transport truncation metadata. */
public record ExecutionText(String text, int totalCharacters, boolean truncated) {
    public ExecutionText {
        text = Objects.requireNonNullElse(text, "");
        if (totalCharacters < text.length() || truncated != (totalCharacters > text.length())) {
            throw new IllegalArgumentException("Invalid execution-text metadata");
        }
    }

    public String displayText() {
        if (!this.truncated) {
            return this.text;
        }
        return this.text + System.lineSeparator()
                + "[retained " + this.text.length() + " of " + this.totalCharacters + " characters]";
    }
}
