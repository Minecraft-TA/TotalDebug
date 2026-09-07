package com.github.minecraft_ta.totaldebug.client.companion;

import java.time.Duration;
import java.util.Objects;

record CompanionTimeouts(
        Duration processStart,
        Duration handshake,
        Duration readiness,
        Duration descriptorPollInterval
) {
    static final CompanionTimeouts DEFAULT = new CompanionTimeouts(
            Duration.ofSeconds(60),
            Duration.ofSeconds(10),
            Duration.ofSeconds(60),
            Duration.ofMillis(50)
    );

    CompanionTimeouts {
        requirePositive(processStart, "processStart");
        requirePositive(handshake, "handshake");
        requirePositive(readiness, "readiness");
        requirePositive(descriptorPollInterval, "descriptorPollInterval");
    }

    private static void requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
