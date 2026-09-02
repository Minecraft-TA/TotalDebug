package com.github.minecraft_ta.totalDebugCompanion.mcp;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DebuggerMcpToolCatalogTest {
    @Test
    void everyDebuggerToolAdvertisesValidSuccessAndErrorSchemas() {
        Map<String, Object> state = Map.of("revision", 3, "phase", "paused", "pause", Map.of(
                "id", "pause-a", "reason", "breakpoint", "thread_id", 42, "all_threads_stopped", true,
                "top_frame", Map.of("id", 1, "method", "run", "binary_name", "example.Test", "line", 4)));
        Map<String, Object> breakpoint = Map.of("binary_name", "example.Test", "line", 4, "state", "bound");
        Map<String, Map<String, Object>> examples = Map.of(
                "debugger_status", state, "debugger_wait", state, "debugger_control", state,
                "debugger_threads", Map.of("threads", List.of(Map.of("id", 42, "name", "main"))),
                "debugger_breakpoints", Map.of("muted", false, "breakpoints", List.of(breakpoint)),
                "debugger_breakpoint_set", Map.of("breakpoint", breakpoint),
                "debugger_breakpoint_remove", Map.of("removed", true),
                "debugger_frames", Map.of("frames", List.of(Map.of("id", 1, "method", "run"))),
                "debugger_variables", Map.of("variables", List.of(Map.of("name", "x", "kind", "local",
                        "value", "1", "type", "int")), "truncated", true),
                "debugger_evaluate", Map.of("value", "3", "type", "int"));
        assertEquals(10, DebuggerMcpToolCatalog.tools().size());
        for (McpSchema.Tool tool : DebuggerMcpToolCatalog.tools()) {
            McpJsonDefaults.getSchemaValidator().assertConforms(tool.name(), tool.inputSchema());
            McpJsonDefaults.getSchemaValidator().assertConforms(tool.name(), tool.outputSchema());
            assertOutput(tool.name(), examples.get(tool.name()));
            assertOutput(tool.name(), Map.of("error", "stale pause"));
            assertOutput(tool.name(), Map.of("error", Map.of("code", "companion_unreachable", "stage", "tcp_connect",
                    "endpoint_health", "unreachable", "retryable", true)));
        }
    }

    @Test
    void controlAndVariableArgumentsHaveOneExactShape() {
        valid("debugger_control", Map.of("action", "attach"));
        valid("debugger_control", Map.of("action", "pause", "thread_id", 42));
        valid("debugger_control", Map.of("action", "step_over", "pause_id", "a", "wait_ms", 0));
        invalid("debugger_control", Map.of("action", "step_over"));
        invalid("debugger_control", Map.of("action", "pause"));
        invalid("debugger_control", Map.of("action", "attach", "pause_id", "a"));
        invalid("debugger_control", Map.of("action", "continue", "pause_id", "a", "thread_id", 42));
        valid("debugger_variables", Map.of("pause_id", "a", "frame_id", 1));
        valid("debugger_variables", Map.of("pause_id", "a", "value_ref", 2, "start", 5, "count", 10));
        invalid("debugger_variables", Map.of("pause_id", "a", "frame_id", 1, "value_ref", 2));
        invalid("debugger_variables", Map.of("pause_id", "a"));
        invalid("debugger_variables", Map.of("pause_id", "a", "frame_id", 1, "count", 0));
        invalid("debugger_variables", Map.of("pause_id", "a", "frame_id", 1, "count", 501));
        invalid("debugger_wait", Map.of("after_revision", 0, "wait_ms", 120001));
        valid("debugger_breakpoint_set", Map.of("binary_name", "example.Test", "line", 4, "enabled", false));
        invalid("debugger_breakpoint_set", Map.of("binary_name", "example.Test", "line", 4, "enabled", "false"));
    }

    static void assertOutput(String name, Map<String, Object> value) {
        McpSchema.Tool tool = DebuggerMcpToolCatalog.tools().stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
        var result = McpJsonDefaults.getSchemaValidator().validate(tool.outputSchema(), value);
        assertTrue(result.valid(), name + ": " + result.errorMessage() + " " + value);
    }

    private static void valid(String name, Map<String, Object> args) {
        CompanionMcpToolCatalog.validateRequest(McpSchema.CallToolRequest.builder(name).arguments(args).build());
        var tool = DebuggerMcpToolCatalog.tools().stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
        assertTrue(McpJsonDefaults.getSchemaValidator().validate(tool.inputSchema(), args).valid());
    }

    private static void invalid(String name, Map<String, Object> args) {
        assertThrows(IllegalArgumentException.class, () -> valid(name, args));
    }
}
