package com.github.minecraft_ta.totalDebugCompanion;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class DiagnosticConsoleTest {
    @Test
    void directLaunchKeepsConsoleChannelsSeparateAndCombinesTheirFileOutput() {
        var log = new TrackedOutput();
        var consoleOut = new TrackedOutput();
        var consoleErr = new TrackedOutput();
        var originalOut = new PrintStream(consoleOut, true, StandardCharsets.UTF_8);
        var originalErr = new PrintStream(consoleErr, true, StandardCharsets.UTF_8);
        try (var stdout = DiagnosticConsole.stream(log, originalOut);
             var stderr = DiagnosticConsole.stream(log, originalErr)) {
            stdout.print("Ready: café\n");
            stderr.print("Failed: λ\n");
            stdout.write('!');
        }
        assertEquals("Ready: café\n!", consoleOut.toString(StandardCharsets.UTF_8));
        assertEquals("Failed: λ\n", consoleErr.toString(StandardCharsets.UTF_8));
        assertEquals("Ready: café\nFailed: λ\n!", log.toString(StandardCharsets.UTF_8));
        assertFalse(log.closed);
        assertFalse(consoleOut.closed);
        assertFalse(consoleErr.closed);
        originalErr.print("Still open");
        assertTrue(consoleErr.toString(StandardCharsets.UTF_8).endsWith("Still open"));
    }

    @Test
    void inheritedFileLaunchWritesEachMessageOnlyOnce() {
        var log = new TrackedOutput();
        try (var stdout = DiagnosticConsole.stream(log, null);
             var stderr = DiagnosticConsole.stream(log, null)) {
            stdout.print("Ready\n");
            stderr.print("Failure\n");
        }
        assertEquals("Ready\nFailure\n", log.toString(StandardCharsets.UTF_8));
        assertFalse(log.closed);
    }

    private static final class TrackedOutput extends ByteArrayOutputStream {
        private boolean closed;

        @Override
        public void close() {
            closed = true;
        }
    }
}
