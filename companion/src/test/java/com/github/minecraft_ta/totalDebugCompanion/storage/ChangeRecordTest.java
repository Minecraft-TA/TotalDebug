package com.github.minecraft_ta.totalDebugCompanion.storage;

import com.github.minecraft_ta.totaldebug.storage.InstancePaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChangeRecordTest {
    private static final ChangeRecord.Level PACK = ChangeRecord.Level.PACK;

    @TempDir Path directory;

    @Test
    void keepsTheOriginalValueUntilItIsWrittenBack() {
        ChangeRecord record = ChangeRecord.inMemory(Clock.fixed(Instant.parse("2026-09-25T12:00:00Z"), ZoneOffset.UTC));
        ChangeRecord.Setting speed = setting("speed");
        List<String> events = new ArrayList<>();
        record.addListener(() -> events.add("changed"));

        record.changed(speed, PACK, "9", "12");
        record.changed(speed, PACK, "12", "14");

        assertEquals(1, record.size());
        ChangeRecord.Change change = record.changes().getFirst();
        assertEquals("9", change.original());
        assertEquals("14", change.current());
        assertEquals(PACK, change.level());
        assertEquals("9", record.original(speed.file(), "speed"));

        record.changed(speed, PACK, "14", "9");
        assertEquals(0, record.size(), "back at the original value, nothing is changed");
        assertNull(record.original(speed.file(), "speed"));
        assertEquals(3, events.size());
    }

    @Test
    void anOriginalValueWrittenElsewhereEndsTheChange() {
        ChangeRecord record = ChangeRecord.inMemory();
        ChangeRecord.Setting speed = setting("speed");
        record.changed(speed, PACK, "9", "12");

        record.observed(speed, PACK, "12", String::equals);
        assertEquals(1, record.size());
        record.observed(speed, PACK, "9", String::equals);
        assertEquals(0, record.size());
    }

    @Test
    void aTargetKeepsItsChangesPerLevel() {
        ChangeRecord record = ChangeRecord.inMemory();
        ChangeRecord.Resource model = new ChangeRecord.Resource("assets/testmod/models/block/slab.json", null);
        record.changed(model, ChangeRecord.Level.GAME, "", "abc");
        record.changed(model, PACK, "", "def");

        assertEquals(2, record.size());
        assertEquals("abc", record.change(model, ChangeRecord.Level.GAME).current());
        record.ended(ChangeRecord.Level.GAME);
        assertEquals(1, record.size(), "what was only in the game's memory ends with the game");
        assertEquals("def", record.change(model, PACK).current());
    }

    @Test
    void survivesReopeningTheInstance() throws Exception {
        InstancePaths paths = new InstancePaths(this.directory.resolve("total-debug"));
        Path pack = this.directory.resolve("resourcepacks/TotalDebug");
        ChangeRecord.Resource lang = new ChangeRecord.Resource("assets/testmod/lang/en_us.json", pack);
        try (ChangeRecord record = ChangeRecord.open(paths, this.directory)) {
            record.changed(setting("speed"), PACK, "9", "12");
            record.changed(setting("mode"), PACK, "\"SLOW\"", "\"FAST\"");
            record.changed(new ChangeRecord.KeyBinding("key.jump"), PACK, "key.keyboard.space", "key.keyboard.g:CONTROL");
            record.changed(lang, PACK, "", "0123");
            record.changed(new ChangeRecord.Resource("assets/testmod/lang/de_de.json", null), ChangeRecord.Level.GAME, "", "4567");
        }
        assertTrue(Files.readString(paths.changes()).contains("\"location\":\"resourcepacks/TotalDebug\""),
                "a pack is stored relative to the instance, like a setting's file");

        try (ChangeRecord reopened = ChangeRecord.open(paths, this.directory)) {
            assertEquals(4, reopened.size(), "the game's memory is not kept");
            assertEquals("key.keyboard.space", reopened.original(new ChangeRecord.KeyBinding("key.jump"), PACK));
            assertEquals("\"SLOW\"", reopened.original(setting("mode").file(), "mode"));
            assertEquals("", reopened.original(lang, PACK));
            assertEquals("0123", reopened.change(lang, PACK).current());
            ChangeRecord.Change speed = reopened.changes().stream()
                    .filter(change -> change.target() instanceof ChangeRecord.Setting setting && setting.setting().equals("speed"))
                    .findFirst().orElseThrow();
            assertEquals(new ChangeRecord.Setting("testmod", "testmod-common.toml",
                    this.directory.resolve("config/testmod-common.toml"), "speed"), speed.target());
            assertEquals("12", speed.current());
        }
    }

    @Test
    void aCopiedInstanceRevertsItsOwnFiles() throws Exception {
        Path original = this.directory.resolve("original");
        Path copy = this.directory.resolve("copy");
        try (ChangeRecord record = ChangeRecord.open(new InstancePaths(original.resolve("total-debug")), original)) {
            record.changed(new ChangeRecord.Setting("testmod", "testmod-common.toml",
                    original.resolve("config/testmod-common.toml"), "speed"), PACK, "9", "12");
        }
        Files.createDirectories(copy.resolve("total-debug"));
        Files.copy(original.resolve("total-debug/changes.json"), copy.resolve("total-debug/changes.json"));

        try (ChangeRecord copied = ChangeRecord.open(new InstancePaths(copy.resolve("total-debug")), copy)) {
            assertEquals("9", copied.original(copy.resolve("config/testmod-common.toml"), "speed"),
                    "the copy's change points at the copy's file, not the original instance");
        }
        assertTrue(Files.readString(copy.resolve("total-debug/changes.json")).contains("\"config/testmod-common.toml\""));
    }

    @Test
    void aStoredPathOutsideTheInstanceIsLeftOutAndNeverWritten() throws Exception {
        InstancePaths paths = new InstancePaths(this.directory.resolve("total-debug"));
        Files.createDirectories(paths.home());
        Files.writeString(paths.changes(), """
                {"format":2,"changes":[
                 {"kind":"setting","modId":"a","fileName":"a.toml","file":"%s","setting":"x","level":"pack","original":"1",
                  "current":"2","firstChanged":"2026-01-01T00:00:00Z","lastChanged":"2026-01-01T00:00:00Z"},
                 {"kind":"setting","modId":"b","fileName":"b.toml","file":"../other/config/b.toml","setting":"x","level":"pack",
                  "original":"1","current":"2","firstChanged":"2026-01-01T00:00:00Z","lastChanged":"2026-01-01T00:00:00Z"},
                 {"kind":"resource","path":"assets/b/lang/en_us.json","location":"../other/resourcepacks/TotalDebug",
                  "level":"pack","original":"","current":"0123","firstChanged":"2026-01-01T00:00:00Z",
                  "lastChanged":"2026-01-01T00:00:00Z"},
                 {"kind":"setting","modId":"c","fileName":"c.toml","file":"config/c.toml","setting":"x","level":"pack",
                  "original":"1","current":"2","firstChanged":"2026-01-01T00:00:00Z","lastChanged":"2026-01-01T00:00:00Z"}]}
                """.formatted(this.directory.resolveSibling("original").resolve("config/a.toml").toString().replace("\\", "/")));

        try (ChangeRecord record = ChangeRecord.open(paths, this.directory)) {
            assertEquals(1, record.size(), "an absolute path and one leading out of the instance name another instance");
            assertEquals("1", record.original(this.directory.resolve("config/c.toml"), "x"));
            assertThrows(IllegalArgumentException.class, () -> record.changed(new ChangeRecord.Setting("d", "d.toml",
                    this.directory.resolveSibling("elsewhere").resolve("d.toml"), "x"), PACK, "1", "2"));
            assertThrows(IllegalArgumentException.class, () -> record.changed(new ChangeRecord.Resource("assets/d/lang/en_us.json",
                    this.directory.resolveSibling("elsewhere")), PACK, "", "0123"));
        }
    }

    @Test
    void anOlderFormatIsReportedInsteadOfRead() throws Exception {
        InstancePaths paths = new InstancePaths(this.directory.resolve("total-debug"));
        Files.createDirectories(paths.changes().getParent());
        Files.writeString(paths.changes(), "{\"format\":1,\"changes\":[]}");
        IOException failure = assertThrows(IOException.class, () -> ChangeRecord.open(paths, this.directory));
        assertTrue(failure.getMessage().contains("format 1"), failure.getMessage());
    }

    @Test
    void anUntouchedInstanceWritesNoRecord() throws Exception {
        InstancePaths paths = new InstancePaths(this.directory.resolve("total-debug"));
        try (ChangeRecord record = ChangeRecord.open(paths, this.directory)) {
            assertEquals(0, record.size());
        }
        assertFalse(Files.exists(paths.changes()));
    }

    private ChangeRecord.Setting setting(String name) {
        return new ChangeRecord.Setting("testmod", "testmod-common.toml",
                this.directory.resolve("config/testmod-common.toml"), name);
    }
}
