package com.github.minecraft_ta.totaldebug.client.companion;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertThrows;

class CompanionTimeoutsTest {
    @Test
    void rejectsNonPositiveDurations() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new CompanionTimeouts(
                        Duration.ZERO,
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1),
                        Duration.ofMillis(1)
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new CompanionTimeouts(
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(-1),
                        Duration.ofSeconds(1),
                        Duration.ofMillis(1)
                )
        );
    }
}
