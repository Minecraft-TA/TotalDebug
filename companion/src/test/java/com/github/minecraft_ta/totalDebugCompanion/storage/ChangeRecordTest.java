package com.github.minecraft_ta.totalDebugCompanion.storage;

import com.github.minecraft_ta.totaldebug.storage.InstancePaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChangeRecordTest {
    @TempDir Path directory;

    @Test
    void keepsTheOriginalValueUntilItIsWrittenBack() {
        ChangeRecord record = ChangeRecord.inMemory(Clock.fixed(Instant.parse("2026-09-25T12:00:00Z"), ZoneOffset.UTC));
        ChangeRecord.Setting speed = setting("speed");
        List<String> events = new ArrayList<>();
        record.addListener(() -> events.add("changed"));

        record.changed(speed, "9", "12");
        record.changed(speed, "12", "14");

        assertEquals(1, record.size());
        ChangeRecord.Change change = record.changes().getFirst();
        assertEquals("9", change.original());
        assertEquals("14", change.current());
        assertEquals("9", record.original(speed.file(), "speed"));

        record.changed(speed, "14", "9");
        assertEquals(0, record.size(), "back at the original value, nothing is changed");
        assertNull(record.original(speed.file(), "speed"));
        assertEquals(3, events.size());
    }

    @Test
    void anOriginalValueWrittenElsewhereEndsTheChange() {
        ChangeRecord record = ChangeRecord.inMemory();
        ChangeRecord.Setting speed = setting("speed");
        record.changed(speed, "9", "12");

        record.observed(speed, "12");
        assertEquals(1, record.size());
        record.observed(speed, "9");
        assertEquals(0, record.size());
    }

    @Test
    void survivesReopeningTheInstance() throws Exception {
        InstancePaths paths = new InstancePaths(this.directory.resolve("total-debug"));
        try (ChangeRecord record = ChangeRecord.open(paths)) {
            record.changed(setting("speed"), "9", "12");
            record.changed(setting("mode"), "\"SLOW\"", "\"FAST\"");
            record.changed(new ChangeRecord.KeyBinding("key.jump"), "key.keyboard.space", "key.keyboard.g:CONTROL");
        }
        assertTrue(Files.isRegularFile(paths.changes()));

        try (ChangeRecord reopened = ChangeRecord.open(paths)) {
            assertEquals(3, reopened.size());
            assertEquals("key.keyboard.space", reopened.original(new ChangeRecord.KeyBinding("key.jump")));
            assertEquals("\"SLOW\"", reopened.original(setting("mode").file(), "mode"));
            ChangeRecord.Change speed = reopened.changes().stream()
                    .filter(change -> change.target() instanceof ChangeRecord.Setting setting && setting.setting().equals("speed"))
                    .findFirst().orElseThrow();
            assertEquals(new ChangeRecord.Setting("testmod", "testmod-common.toml",
                    this.directory.resolve("config/testmod-common.toml"), "speed"), speed.target());
            assertEquals("12", speed.current());
        }
    }

    @Test
    void anUntouchedInstanceWritesNoRecord() throws Exception {
        InstancePaths paths = new InstancePaths(this.directory.resolve("total-debug"));
        try (ChangeRecord record = ChangeRecord.open(paths)) {
            assertEquals(0, record.size());
        }
        assertFalse(Files.exists(paths.changes()));
    }

    private ChangeRecord.Setting setting(String name) {
        return new ChangeRecord.Setting("testmod", "testmod-common.toml",
                this.directory.resolve("config/testmod-common.toml"), name);
    }
}
