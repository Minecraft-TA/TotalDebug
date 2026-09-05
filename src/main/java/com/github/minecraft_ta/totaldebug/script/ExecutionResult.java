package com.github.minecraft_ta.totaldebug.script;

import java.util.Objects;

/** Canonical, transport-safe update for one live Java execution. */
public record ExecutionResult(
        ExecutionStatus status,
        ExecutionText logs,
        ExecutionValue value,
        ExecutionText error
) {
    public ExecutionResult {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(logs, "logs");
        Objects.requireNonNull(error, "error");
    }

    public static ExecutionResult progress(ExecutionStatus status) {
        return new ExecutionResult(status, ExecutionText.empty(), null, ExecutionText.empty());
    }

    public static ExecutionResult fromStatus(ExecutionStatus status, String message) {
        return switch (Objects.requireNonNull(status, "status")) {
            case COMPILATION_COMPLETED -> progress(status);
            case CANCELLATION_PENDING -> new ExecutionResult(status, ExecutionText.empty(), null, ExecutionText.complete(message));
            case COMPILATION_FAILED -> failure(status, message);
            case RUN_EXCEPTION -> failed("", null, message);
            case RUN_COMPLETED -> completed(message, null);
        };
    }

    public static ExecutionResult failure(ExecutionStatus status, String error) {
        return failure(status, ExecutionText.complete(error));
    }

    /** A delivery failure cannot imply target completion when this update was only progress. */
    public ExecutionResult deliveryFailure(String message) {
        return new ExecutionResult(this.status.terminal() ? ExecutionStatus.RUN_EXCEPTION : this.status,
                ExecutionText.empty(), null, ExecutionText.complete(message));
    }

    public static ExecutionResult failure(ExecutionStatus status, ExecutionText error) {
        return new ExecutionResult(status, ExecutionText.empty(), null, error);
    }

    public static ExecutionResult completed(String logs, ExecutionValue value) {
        return completed(ExecutionText.complete(logs), value);
    }

    public static ExecutionResult completed(ExecutionText logs, ExecutionValue value) {
        return new ExecutionResult(
                ExecutionStatus.RUN_COMPLETED,
                logs,
                value,
                ExecutionText.empty()
        );
    }

    public static ExecutionResult failed(String logs, ExecutionValue value, String error) {
        return failed(ExecutionText.complete(logs), value, error);
    }

    public static ExecutionResult failed(ExecutionText logs, ExecutionValue value, String error) {
        return failed(logs, value, ExecutionText.complete(error));
    }

    public static ExecutionResult failed(ExecutionText logs, ExecutionValue value, ExecutionText error) {
        return new ExecutionResult(
                ExecutionStatus.RUN_EXCEPTION,
                logs,
                value,
                error
        );
    }
}
