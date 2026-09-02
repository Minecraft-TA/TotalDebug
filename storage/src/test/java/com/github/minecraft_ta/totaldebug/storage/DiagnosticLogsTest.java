package com.github.minecraft_ta.totaldebug.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class DiagnosticLogsTest {
    @TempDir Path home;

    @Test
    void boundsCurrentAndPreviousLogAndProtectsLiveReservations() throws Exception {
        AppPaths paths = new AppPaths(this.home);
        Path original;
        try (var first = DiagnosticLogs.reserve(paths)) {
            original = first.log();
            try (var output = new DiagnosticLogs.RotatingOutput(original,
                    FileLease.acquire(original.getParent().resolve(".lock")), 16)) {
                output.write("a".repeat(53).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            assertEquals(5, Files.size(original));
            assertEquals(16, Files.size(original.resolveSibling("previous.log")));
            for (int i = 0; i < 12; i++) {
                try (var ignored = DiagnosticLogs.reserve(paths)) { }
            }
            assertTrue(Files.exists(original));
        }
        try (var ignored = DiagnosticLogs.reserve(paths)) {
            assertFalse(Files.exists(original));
        }
    }

    @Test
    void rejectsLogsOutsideItsDirectory() {
        assertThrows(java.io.IOException.class, () -> DiagnosticLogs.open(new AppPaths(this.home), this.home.resolve("bad.log")));
    }
}
