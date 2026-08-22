package com.github.minecraft_ta.totalDebugCompanion.session;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertThrows;

class CompanionTimeoutsTest {
    @Test
    void rejectsNonPositiveAuthenticationTimeout() {
        assertThrows(IllegalArgumentException.class, () -> new CompanionTimeouts(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new CompanionTimeouts(Duration.ofSeconds(-1)));
    }
}
