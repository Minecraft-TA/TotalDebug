package com.github.minecraft_ta.totalDebugCompanion.session;

import java.time.Duration;
import java.util.Objects;

public record CompanionTimeouts(Duration authentication) {
    public static final CompanionTimeouts DEFAULT = new CompanionTimeouts(Duration.ofSeconds(60));

    public CompanionTimeouts {
        Objects.requireNonNull(authentication, "authentication");
        if (authentication.isZero() || authentication.isNegative()) {
            throw new IllegalArgumentException("authentication must be positive");
        }
    }
}
