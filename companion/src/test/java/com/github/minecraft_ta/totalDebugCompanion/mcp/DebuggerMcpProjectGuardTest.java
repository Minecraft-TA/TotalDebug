package com.github.minecraft_ta.totalDebugCompanion.mcp;

import com.github.minecraft_ta.totalDebugCompanion.CompanionApp;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerSessionController;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class DebuggerMcpProjectGuardTest {
    @Test void sourceLoadingCannotCarryABreakpointIntoTheNextProject() throws Exception {
        long original = CompanionApp.projectGeneration();
        var generation = CompanionApp.class.getDeclaredField("projectGeneration");
        generation.setAccessible(true);
        var source = new DebugEngine.Source(URI.create("file:///old-project/Target.java"), "Target", "class Target {}");
        try (var controller = new DebuggerSessionController(name -> source)) {
            var service = new DebuggerMcpService(() -> controller, name -> {
                synchronized (CompanionApp.class) { generation.setLong(null, original + 1); }
                return source;
            });
            var failure = assertThrows(IllegalStateException.class,
                    () -> service.call("debugger_breakpoint_set", Map.of("binary_name", "Target", "line", 1), original));
            assertTrue(failure.getMessage().contains("Project changed"));
            assertTrue(controller.breakpointEntries().isEmpty());
            assertThrows(IllegalStateException.class,
                    () -> service.call("debugger_control", Map.of("action", "attach"), original));
        } finally {
            synchronized (CompanionApp.class) { generation.setLong(null, original); }
        }
    }
}
