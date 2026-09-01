package com.github.minecraft_ta.totalDebugCompanion.script;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.util.Objects;

/** Canonical execution-result envelope received from Minecraft. */
public record ExecutionResult(
        Status status,
        ExecutionText logs,
        ExecutionValue value,
        ExecutionText error
) {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    public ExecutionResult {
        status = Objects.requireNonNull(status, "status");
        logs = Objects.requireNonNull(logs, "logs");
        error = Objects.requireNonNull(error, "error");
    }

    public static ExecutionResult parse(String json) {
        ExecutionResult result;
        try {
            result = GSON.fromJson(json, ExecutionResult.class);
        } catch (RuntimeException exception) {
            throw new JsonParseException("Invalid execution result", exception);
        }
        if (result == null || result.status == null) {
            throw new JsonParseException("Execution result has no status");
        }
        validateText(result.logs, "logs");
        validateText(result.error, "error");
        if (result.value != null) {
            ExecutionValue.validate(result.value, 0, new int[1]);
        }
        return result;
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    private static void validateText(ExecutionText text, String name) {
        if (text == null || text.text() == null || text.totalCharacters() < text.text().length()
                || text.truncated() != (text.totalCharacters() > text.text().length())) {
            throw new JsonParseException("Execution result contains invalid " + name + " metadata");
        }
    }

    public enum Status {
        COMPILATION_FAILED,
        COMPILATION_COMPLETED,
        RUN_EXCEPTION,
        RUN_COMPLETED
    }
}
