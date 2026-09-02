package com.github.minecraft_ta.totalDebugCompanion.storage;

import com.github.minecraft_ta.totalDebugCompanion.script.ExpressionHistory;
import com.github.minecraft_ta.totalDebugCompanion.script.SnippetExecutionService;
import com.github.minecraft_ta.totaldebug.storage.InstancePaths;
import com.github.minecraft_ta.totaldebug.storage.JsonFiles;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class InstanceStateTest {
    @TempDir Path home;

    @Test
    void debuggerAndExpressionStateShareOneFileWithoutCrossInstanceLeakage() throws Exception {
        var first = new InstancePaths(this.home.resolve("first"));
        var second = new InstancePaths(this.home.resolve("second"));
        var breakpoint = new InstanceState.PersistedBreakpoint("decompiled:///example/Target.java",
                "example.Target", 12, 8, null, null, null, "x > 1", "2", false);
        var entry = new ExpressionHistory.Entry("Blocks.AIR", SnippetExecutionService.Side.CLIENT,
                List.of("net.minecraft.world.level.block.Blocks"));
        try (var state = InstanceState.open(first)) {
            state.setDebuggerWatches(List.of(" player ", "player", "level"));
            state.setDebuggerBreakpoints("runtime-a", List.of(breakpoint));
            state.setDebuggerBreakpointsMuted(true);
            state.setBreakOnCaughtExceptions(true);
            state.setBreakOnUncaughtExceptions(true);
            state.expressionHistory().record(entry);
        }
        try (var state = InstanceState.open(first); var other = InstanceState.open(second)) {
            assertEquals(List.of("player", "level"), state.debuggerWatches());
            assertEquals(List.of(breakpoint), state.debuggerBreakpoints("runtime-a"));
            assertTrue(state.debuggerBreakpointsMuted());
            assertTrue(state.breakOnCaughtExceptions());
            assertTrue(state.breakOnUncaughtExceptions());
            assertEquals(List.of(entry), state.expressionHistory().entries());
            assertTrue(other.debuggerWatches().isEmpty());
            assertFalse(other.debuggerBreakpointsMuted());
            assertTrue(other.expressionHistory().entries().isEmpty());
        }
        try (var files = Files.list(first.home())) {
            assertEquals(List.of("state.json"), files.map(path -> path.getFileName().toString()).toList());
        }
        assertFalse(Files.exists(second.state()));
        assertFalse(JsonFiles.read(first.state()).has("theme"));
    }

    @Test
    void malformedStateIsReportedAndNeverReplacedByDefaults() throws Exception {
        var paths = new InstancePaths(this.home);
        String invalid = "{\"format\":1,\"debuggerWatches\":null}";
        Files.writeString(paths.state(), invalid);
        assertThrows(java.io.IOException.class, () -> InstanceState.open(paths));
        assertEquals(invalid, Files.readString(paths.state()));
    }

    @Test
    void failedFlushRemainsPendingAndCanBeRetried() throws Exception {
        Path parent = Files.writeString(this.home.resolve("blocked"), "not a directory");
        Path target = parent.resolve("state.json");
        try (var writer = new JsonStateWriter(target)) {
            var first = new JsonObject();
            first.addProperty("value", "first");
            writer.schedule(first);
            assertThrows(java.io.IOException.class, writer::flush);
            Files.delete(parent);
            Files.createDirectory(parent);
            writer.flush();
            assertEquals("first", JsonFiles.read(target).get("value").getAsString());
            var latest = new JsonObject();
            latest.addProperty("value", "latest");
            writer.schedule(first);
            writer.schedule(latest);
            latest.addProperty("value", "external mutation");
            writer.flush();
            assertEquals("latest", JsonFiles.read(target).get("value").getAsString());
        }
    }
}
