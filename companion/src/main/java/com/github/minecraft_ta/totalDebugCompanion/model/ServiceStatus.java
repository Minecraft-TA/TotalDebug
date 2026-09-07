package com.github.minecraft_ta.totalDebugCompanion.model;

import java.util.Objects;

/** A service state published by the code that owns the service lifecycle. */
public record ServiceStatus(State state, String summary, String detail) {
    public ServiceStatus {
        Objects.requireNonNull(state, "state");
        if (Objects.requireNonNull(summary, "summary").isBlank()) {
            throw new IllegalArgumentException("Service status summary must not be blank");
        }
        if (Objects.requireNonNull(detail, "detail").isBlank()) {
            throw new IllegalArgumentException("Service status detail must not be blank");
        }
    }

    public enum State {
        INACTIVE,
        PENDING,
        AVAILABLE,
        FAILED
    }
}
