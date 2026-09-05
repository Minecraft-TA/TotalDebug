package com.github.minecraft_ta.totaldebug.storage;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class RuntimePhaseTest {
    @TempDir Path directory;

    @Test
    void recordsNamedDurationsWithoutControllingTheApplication() throws Exception {
        Path file = directory.resolve("phases.jfr");
        try (var recording = new Recording()) {
            recording.enable(RuntimePhase.class).withThreshold(java.time.Duration.ZERO);
            recording.start();
            try (var phase = RuntimePhase.start("test.phase")) {
                phase.close(); // Closing twice must not duplicate a span.
            }
            recording.stop();
            recording.dump(file);
        }
        var events = RecordingFile.readAllEvents(file).stream()
                .filter(event -> event.getEventType().getName().equals("com.github.minecraft_ta.totaldebug.RuntimePhase"))
                .toList();
        assertEquals(1, events.size());
        assertEquals("test.phase", events.getFirst().getString("phase"));
        assertFalse(events.getFirst().getDuration().isNegative());
    }
}
