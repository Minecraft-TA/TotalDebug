package com.github.minecraft_ta.totaldebug.script;

import java.util.Objects;

/** One script lifecycle update with output, structured result, and diagnostics kept separate. */
public record ScriptStatus(
        ScriptStatusType type,
        String output,
        String resultJson,
        String error
) {
    public ScriptStatus {
        type = Objects.requireNonNull(type, "type");
        output = Objects.requireNonNullElse(output, "");
        error = Objects.requireNonNullElse(error, "");
    }

    public static ScriptStatus progress(ScriptStatusType type) {
        return new ScriptStatus(type, "", null, "");
    }

    public static ScriptStatus failure(ScriptStatusType type, String error) {
        return new ScriptStatus(type, "", null, error);
    }

    public static ScriptStatus completed(String output, String resultJson) {
        return new ScriptStatus(ScriptStatusType.RUN_COMPLETED, output, resultJson, "");
    }

    public static ScriptStatus failed(String output, String resultJson, String error) {
        return new ScriptStatus(ScriptStatusType.RUN_EXCEPTION, output, resultJson, error);
    }
}
