package com.github.minecraft_ta.totalDebugCompanion.mcp;

import io.modelcontextprotocol.spec.McpSchema;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.github.minecraft_ta.totalDebugCompanion.mcp.CompanionMcpToolCatalog.*;

/** Debugger tool contracts shared by the HTTP host and reconnecting stdio sidecar. */
final class DebuggerMcpToolCatalog {
    private DebuggerMcpToolCatalog() {
    }

    static List<McpSchema.Tool> tools() {
        return List.of(
                tool("debugger_status", "Read the shared UI/MCP debugger state and current pause identity.",
                        objectSchema(Map.of(), List.of()), toolOutputSchema(snapshotSchema())),
                tool("debugger_wait", "Wait for a debugger revision change without polling. Returns current state at timeout.",
                        objectSchema(Map.of("after_revision", longInteger("Last observed revision.", 0),
                                "wait_ms", waitSchema()), List.of("after_revision")), toolOutputSchema(snapshotSchema())),
                tool("debugger_control", "Attach/detach or control execution. Pause needs thread_id; continue/step need pause_id. "
                                + "Step and pause wait up to 10s by default; other actions return after acknowledgement.",
                        controlSchema(), toolOutputSchema(snapshotSchema())),
                tool("debugger_threads", "List threads in the attached Minecraft JVM.",
                        objectSchema(Map.of(), List.of()), listOutput("threads", objectSchema(Map.of(
                                "id", longInteger("Thread identifier.", 1), "name", stringValueSchema("Thread name.")),
                                List.of("id", "name")))),
                tool("debugger_breakpoints", "List the same breakpoints and mute state shown in Companion.",
                        objectSchema(Map.of(), List.of()), toolOutputSchema(objectSchema(Map.of(
                                "muted", booleanSchema("Whether all breakpoints are muted."),
                                "breakpoints", arraySchema(breakpointSchema())), List.of("muted", "breakpoints")))),
                tool("debugger_breakpoint_set", "Create or replace one breakpoint at an absolute displayed Vineflower line. "
                                + "Omitted conditions are cleared; enabled defaults to true. No editor needs to be open.",
                        breakpointInput(true), toolOutputSchema(objectSchema(Map.of("breakpoint", breakpointSchema()),
                                List.of("breakpoint")))),
                tool("debugger_breakpoint_remove", "Remove one breakpoint by binary name and displayed source line.",
                        breakpointInput(false), toolOutputSchema(objectSchema(Map.of(
                                "removed", booleanSchema("Whether a breakpoint was removed.")), List.of("removed")))),
                tool("debugger_frames", "Read the stack of the thread that caused the current pause.",
                        objectSchema(Map.of("pause_id", pauseId()), List.of("pause_id")), listOutput("frames", frameSchema())),
                tool("debugger_variables", "Read frame locals or expand a returned value_ref. One level only, 100 items by default, "
                                + "at most 500. Handles expire when execution resumes.",
                        variablesInput(), toolOutputSchema(objectSchema(Map.of(
                                "variables", arraySchema(variableSchema()),
                                "truncated", Map.of("type", "boolean", "const", true)), List.of("variables")))),
                tool("debugger_evaluate", "Evaluate Java in one paused frame. Method calls may mutate Minecraft. "
                                + "Wait expiration returns a pending operation; it never detaches, resumes or retries.",
                        objectSchema(Map.of("pause_id", pauseId(), "frame_id", reference("Frame identifier."),
                                "source", stringSchema("Java expression or code fragment."), "wait_ms", waitSchema()),
                                List.of("pause_id", "frame_id", "source")), toolOutputSchema(operationSchema())),
                tool("debugger_evaluation_wait", "Wait for the same evaluation without executing its code again.",
                        objectSchema(Map.of("operation_id", stringSchema("Returned evaluation operation ID."),
                                "wait_ms", waitSchema()), List.of("operation_id")), toolOutputSchema(operationSchema())),
                tool("debugger_evaluation_cancel", "Request cancellation. An executing target method must return before evaluation can stop.",
                        objectSchema(Map.of("operation_id", stringSchema("Returned evaluation operation ID.")),
                                List.of("operation_id")), toolOutputSchema(operationSchema()))
        );
    }

    private static Map<String, Object> operationSchema() {
        return objectSchema(Map.of(
                "operation_id", stringSchema("Evaluation identity, retained for the latest 128 operations; live results expire with their pause."),
                "state", enumSchema("Execution state.", "running", "succeeded", "failed", "cancelled"),
                "elapsed_ms", longInteger("Elapsed execution milliseconds.", 0),
                "slow", booleanSchema("Evaluation exceeded the five-second notification threshold."),
                "cancellation_requested", booleanSchema("Cancellation has been requested, not necessarily completed."),
                "error", stringSchema("Execution diagnostic."),
                "result", objectSchema(valueProperties(), List.of("value", "type")),
                "result_expired", booleanSchema("Live result expired after its pause ended.")),
                List.of("operation_id", "state", "elapsed_ms", "slow", "cancellation_requested"));
    }

    private static Map<String, Object> controlSchema() {
        return Map.of("type", "object", "oneOf", List.of(
                objectSchema(Map.of("action", enumSchema("Lifecycle command.", "attach", "detach"),
                        "wait_ms", waitSchema()), List.of("action")),
                objectSchema(Map.of("action", enumSchema("Pause a running thread.", "pause"),
                        "thread_id", longInteger("Thread returned by debugger_threads.", 1),
                        "wait_ms", waitSchema()), List.of("action", "thread_id")),
                objectSchema(Map.of("action", enumSchema("Paused execution command.",
                                "continue", "step_over", "step_into", "step_out"),
                        "pause_id", pauseId(), "wait_ms", waitSchema()), List.of("action", "pause_id"))));
    }

    private static Map<String, Object> variablesInput() {
        Map<String, Object> common = Map.of("pause_id", pauseId(),
                "start", integerSchema("Zero-based page offset.", 0, 1_000_000_000),
                "count", integerSchema("Page size, default 100.", 1, 500));
        Map<String, Object> frame = new LinkedHashMap<>(common);
        frame.put("frame_id", reference("Frame whose locals to read."));
        Map<String, Object> value = new LinkedHashMap<>(common);
        value.put("value_ref", reference("Expandable value returned during this pause."));
        return Map.of("type", "object", "oneOf", List.of(
                objectSchema(frame, List.of("pause_id", "frame_id")),
                objectSchema(value, List.of("pause_id", "value_ref"))));
    }

    private static Map<String, Object> breakpointInput(boolean configure) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("binary_name", stringSchema("Exact runtime class binary name."));
        properties.put("line", reference("Absolute displayed source line, including runtime_source.start_line offset."));
        if (configure) {
            properties.put("condition", stringSchema("Java boolean condition."));
            properties.put("hit_condition", stringSchema("Breakpoint hit count condition."));
            properties.put("enabled", booleanSchema("Whether the breakpoint is enabled, default true."));
            properties.put("action", actionSchema());
        }
        return objectSchema(properties, List.of("binary_name", "line"));
    }

    private static Map<String, Object> snapshotSchema() {
        return objectSchema(Map.of(
                "revision", longInteger("Debugger change revision.", 0),
                "phase", enumSchema("Debugger phase.", "unavailable", "detached", "attaching", "running", "paused",
                        "detaching", "failed"),
                "target", objectSchema(Map.of("id", stringSchema("Published target identifier."),
                        "name", stringSchema("Target name."), "process_id", longInteger("Minecraft JVM process ID.", 1)),
                        List.of("id", "name", "process_id")),
                "pause", objectSchema(Map.of("id", pauseId(), "reason", stringValueSchema("Why execution stopped."),
                        "thread_id", longInteger("Stopped thread identifier.", 1),
                        "all_threads_stopped", booleanSchema("Whether all JVM threads are suspended."),
                        "top_frame", frameSchema()), List.of("id", "reason", "thread_id", "all_threads_stopped")),
                "evaluation", operationSchema(),
                "breakpoint_action", objectSchema(Map.of("source", stringSchema("Most recent breakpoint action."),
                        "result", objectSchema(valueProperties(), List.of("value", "type")),
                        "error", stringSchema("Action failure.")), List.of("source")),
                "error", stringValueSchema("Current debugger failure, when present.")), List.of("revision", "phase"));
    }

    private static Map<String, Object> frameSchema() {
        return objectSchema(Map.of("id", reference("Frame identifier valid for this pause."),
                "method", stringValueSchema("Frame method display name."),
                "binary_name", stringSchema("Runtime class binary name, when resolvable."),
                "line", reference("Displayed source line, when available.")), List.of("id", "method"));
    }

    private static Map<String, Object> valueProperties() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("value", stringValueSchema("Debugger value representation."));
        result.put("type", stringValueSchema("Java type."));
        result.put("scalar", objectSchema(Map.of("kind", stringSchema("Java scalar kind, including null and void."),
                "value", Map.of("type", List.of("string", "number", "boolean", "null"))), List.of("kind", "value")));
        result.put("value_ref", reference("Expandable object or array handle, valid only for this pause."));
        result.put("indexed_count", reference("Number of array elements."));
        return result;
    }

    private static Map<String, Object> variableSchema() {
        Map<String, Object> result = valueProperties();
        result.put("name", stringValueSchema("Variable name."));
        result.put("kind", enumSchema("Variable category.", "unknown", "this", "parameter", "local", "field",
                "array_element", "return_value", "expression"));
        result.put("evaluate_name", stringSchema("Java expression addressing this value."));
        result.put("named_count", reference("Number of named children."));
        return objectSchema(result, List.of("name", "kind", "value", "type"));
    }

    private static Map<String, Object> actionSchema() {
        var completion = enumSchema("Completion policy, defaults to stay_paused.", "stay_paused", "continue_on_success");
        return Map.of("type", "object", "oneOf", List.of(
                objectSchema(Map.of("source", stringSchema("Java expression or body, with optional imports."),
                        "completion", completion), List.of("source")),
                objectSchema(Map.of("script", stringSchema("Path relative to the scripts directory, read afresh on each hit."),
                        "completion", completion), List.of("script"))));
    }

    private static Map<String, Object> breakpointSchema() {
        return objectSchema(Map.of("binary_name", stringSchema("Runtime class binary name."),
                "line", reference("Requested displayed line."), "resolved_line", reference("Bound displayed line."),
                "state", enumSchema("Breakpoint binding state.", "disabled", "unbound", "pending", "bound", "invalid"),
                "condition", stringSchema("Java condition."), "hit_condition", stringSchema("Hit count condition."),
                "action", actionSchema(),
                "error", stringSchema("Binding diagnostic, when present.")), List.of("binary_name", "line", "state"));
    }

    private static Map<String, Object> listOutput(String name, Map<String, Object> item) {
        return toolOutputSchema(objectSchema(Map.of(name, arraySchema(item)), List.of(name)));
    }

    private static Map<String, Object> pauseId() {
        return stringSchema("Exact current pause ID from debugger_status or a control result.");
    }

    private static Map<String, Object> reference(String description) {
        return integerSchema(description, 1, Integer.MAX_VALUE);
    }

    private static Map<String, Object> waitSchema() {
        return integerSchema("Maximum wait in milliseconds.", 0, 120_000);
    }

    private static Map<String, Object> longInteger(String description, long minimum) {
        return Map.of("type", "integer", "description", description, "minimum", minimum, "maximum", 9_007_199_254_740_991L);
    }
}
