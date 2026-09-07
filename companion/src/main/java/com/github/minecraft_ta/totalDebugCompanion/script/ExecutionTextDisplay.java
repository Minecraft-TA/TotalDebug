package com.github.minecraft_ta.totalDebugCompanion.script;

import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionText;

public final class ExecutionTextDisplay {
    private ExecutionTextDisplay() {
    }

    public static String format(ExecutionText text) {
        return text.truncated()
                ? text.text() + System.lineSeparator() + "[retained " + text.text().length()
                        + " of " + text.totalCharacters() + " characters]"
                : text.text();
    }
}
