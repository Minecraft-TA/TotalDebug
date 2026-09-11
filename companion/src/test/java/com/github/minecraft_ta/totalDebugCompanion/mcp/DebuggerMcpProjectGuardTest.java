package com.github.minecraft_ta.totalDebugCompanion.mcp;

import com.github.minecraft_ta.totalDebugCompanion.project.ProjectScope;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionProfile;
import com.github.minecraft_ta.totalDebugCompanion.storage.InstanceState;
import java.nio.file.Path;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerSessionController;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class DebuggerMcpProjectGuardTest {
    @Test void sourceLoadingCannotCarryABreakpointIntoTheNextProject() throws Exception {
        var scope = new ProjectScope(new Object(), new CompanionProfile("test", Path.of("data"), Path.of("game")), InstanceState.inMemory());
        var source = new DebugEngine.Source(URI.create("file:///old-project/Target.java"), "Target", "class Target {}");
        try (var controller = new DebuggerSessionController(name -> source)) {
            var service = new DebuggerMcpService(() -> controller, name -> {
                scope.retire();
                return source;
            });
            var failure = assertThrows(IllegalStateException.class,
                    () -> service.call("debugger_breakpoint_set", Map.of("binary_name", "Target", "line", 1), scope));
            assertTrue(failure.getMessage().contains("Project changed"));
            assertTrue(controller.breakpointEntries().isEmpty());
            assertThrows(IllegalStateException.class,
                    () -> service.call("debugger_control", Map.of("action", "attach"), scope));
        } finally {
            scope.retire();
            scope.close();
        }
    }
}
