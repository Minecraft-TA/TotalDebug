package com.github.minecraft_ta.totaldebug.protocol.execution;

public enum ExecutionStatus {
    COMPILATION_FAILED,
    COMPILATION_COMPLETED,
    RUN_EXCEPTION,
    RUN_COMPLETED,
    CANCELLATION_PENDING;

    public boolean terminal() {
        return this != COMPILATION_COMPLETED && this != CANCELLATION_PENDING;
    }
}
