package com.github.minecraft_ta.totaldebug.client.companion;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompanionStartupProgressTest {
    @Test
    void downloadProgressUsesKnownContentLength() {
        CompanionStartupProgress progress = CompanionStartupProgress.downloading("2.0.0", 25L, 100L);

        assertTrue(progress.hasDeterminateProgress());
        assertEquals(0.25F, progress.fraction());
        assertEquals(25, progress.percentage());
    }

    @Test
    void unknownDownloadLengthDoesNotInventProgress() {
        CompanionStartupProgress progress = CompanionStartupProgress.downloading(
                "2.0.0",
                25L,
                CompanionStartupProgress.UNKNOWN_TOTAL
        );

        assertFalse(progress.hasDeterminateProgress());
        assertEquals(0.0F, progress.fraction());
        assertEquals(0, progress.percentage());
    }

    @Test
    void progressIsClampedWhenTheServerReportsTheWrongLength() {
        CompanionStartupProgress progress = CompanionStartupProgress.downloading("2.0.0", 125L, 100L);

        assertEquals(1.0F, progress.fraction());
        assertEquals(100, progress.percentage());
    }
}
