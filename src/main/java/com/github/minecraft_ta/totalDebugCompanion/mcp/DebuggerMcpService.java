package com.github.minecraft_ta.totalDebugCompanion.mcp;

import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerBreakpointResolver;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerSessionController;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/** MCP projection of the same debugger session used by Companion's UI. */
final class DebuggerMcpService {
    private final Supplier<DebuggerSessionController> controller;
    private final DebuggerSessionController.SourceLoader sources;

    DebuggerMcpService(Supplier<DebuggerSessionController> controller,
                       DebuggerSessionController.SourceLoader sources) {
        this.controller = Objects.requireNonNull(controller, "controller");
        this.sources = Objects.requireNonNull(sources, "sources");
    }

    Map<String, Object> call(String tool, Map<String, Object> args) throws IOException {
        DebuggerSessionController session = this.controller.get();
        try {
            return switch (tool) {
                case "debugger_status" -> snapshot(session.snapshot());
                case "debugger_wait" -> snapshot(session.waitForChange(
                        ((Number) args.get("after_revision")).longValue(), integer(args, "wait_ms", 30_000)));
                case "debugger_control" -> control(session, args);
                case "debugger_threads" -> Map.of("threads", await(session.threads()).stream()
                        .map(thread -> Map.of("id", thread.id(), "name", thread.name())).toList());
                case "debugger_breakpoints" -> Map.of(
                        "muted", session.breakpointsMuted(),
                        "breakpoints", session.breakpointEntries().stream().map(DebuggerMcpService::breakpoint).toList());
                case "debugger_breakpoint_set" -> setBreakpoint(session, args);
                case "debugger_breakpoint_remove" -> removeBreakpoint(session, args);
                case "debugger_frames" -> Map.of("frames", await(session.frames(text(args, "pause_id")))
                        .stream().map(DebuggerMcpService::frame).toList());
                case "debugger_variables" -> variables(session, args);
                case "debugger_evaluate" -> evaluation(await(session.evaluate(text(args, "pause_id"),
                        integer(args, "frame_id", 0), text(args, "expression"))));
                default -> throw new IllegalArgumentException("Unknown debugger tool: " + tool);
            };
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Debugger wait interrupted", exception);
        }
    }

    private Map<String, Object> control(DebuggerSessionController session, Map<String, Object> args)
            throws InterruptedException {
        String action = text(args, "action");
        CompletableFuture<Void> operation = switch (action) {
            case "attach" -> session.attach();
            case "detach" -> session.detach();
            case "pause" -> session.pause(((Number) args.get("thread_id")).longValue());
            case "continue", "step_over", "step_into", "step_out" ->
                    session.controlPaused(text(args, "pause_id"), action);
            default -> throw new IllegalArgumentException("Unknown debugger action: " + action);
        };
        await(operation);
        int defaultWait = action.equals("pause") || action.startsWith("step_") ? 10_000 : 0;
        return snapshot(session.waitUntilStopped(integer(args, "wait_ms", defaultWait)));
    }

    private Map<String, Object> setBreakpoint(DebuggerSessionController session, Map<String, Object> args)
            throws IOException {
        String binaryName = text(args, "binary_name");
        DebugEngine.Source source;
        try {
            source = this.sources.load(binaryName);
        } catch (IOException | RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IOException("Unable to load debugger source for " + binaryName, exception);
        }
        if (source == null) throw new IllegalArgumentException("Class not found: " + binaryName);
        int line = integer(args, "line", 0);
        DebugEngine.SourceBreakpoint request = DebuggerBreakpointResolver.resolve(source, line,
                (String) args.get("condition"), (String) args.get("hit_condition"))
                .orElseThrow(() -> new IllegalArgumentException("No executable bytecode is mapped to line " + line));
        await(session.putBreakpoint(source, request, (Boolean) args.getOrDefault("enabled", true)));
        DebuggerSessionController.Breakpoint resolved = session.breakpoint(source.uri(), line);
        if (resolved == null) throw new IllegalStateException("Breakpoint was removed concurrently");
        return Map.of("breakpoint", breakpoint(new DebuggerSessionController.BreakpointEntry(
                source.uri(), source.binaryName(), resolved)));
    }

    private Map<String, Object> removeBreakpoint(DebuggerSessionController session, Map<String, Object> args) {
        String binaryName = text(args, "binary_name");
        int line = integer(args, "line", 0);
        List<DebuggerSessionController.BreakpointEntry> matches = session.breakpointEntries().stream()
                .filter(entry -> entry.binaryName().equals(binaryName) && entry.breakpoint().line() == line).toList();
        for (var entry : matches) await(session.removeBreakpoint(entry.sourceUri(), line));
        return Map.of("removed", !matches.isEmpty());
    }

    private static Map<String, Object> variables(DebuggerSessionController session, Map<String, Object> args) {
        int count = integer(args, "count", 100);
        List<DebugEngine.Variable> values = await(session.variables(text(args, "pause_id"),
                args.containsKey("frame_id") ? integer(args, "frame_id", 0) : null,
                args.containsKey("value_ref") ? integer(args, "value_ref", 0) : null,
                integer(args, "start", 0), count + 1));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("variables", values.stream().limit(count).map(DebuggerMcpService::variable).toList());
        if (values.size() > count) result.put("truncated", true);
        return result;
    }

    static Map<String, Object> snapshot(DebuggerSessionController.Snapshot snapshot) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("revision", snapshot.revision());
        result.put("phase", lower(snapshot.status().phase()));
        var target = snapshot.status().target();
        if (target != null) result.put("target", Map.of("id", target.id(), "name", target.displayName(),
                "process_id", target.processId()));
        if (snapshot.pause() != null) {
            var stopped = snapshot.pause().event();
            Map<String, Object> pause = new LinkedHashMap<>();
            pause.put("id", snapshot.pauseId());
            pause.put("reason", stopped.reason());
            pause.put("thread_id", stopped.threadId());
            pause.put("all_threads_stopped", stopped.allThreadsStopped());
            if (!snapshot.pause().frames().isEmpty()) pause.put("top_frame", frame(snapshot.pause().frames().getFirst()));
            result.put("pause", pause);
        }
        if (snapshot.status().failure() != null) result.put("error", snapshot.status().detail());
        return result;
    }

    private static Map<String, Object> frame(DebugEngine.StackFrame frame) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", frame.id());
        result.put("method", frame.name());
        if (!frame.binaryName().isBlank()) result.put("binary_name", frame.binaryName());
        if (frame.line() > 0) result.put("line", frame.line());
        return result;
    }

    private static Map<String, Object> variable(DebugEngine.Variable variable) {
        Map<String, Object> result = value(variable.value(), variable.type(), variable.variablesReference(),
                variable.indexedVariables());
        result.put("name", variable.name());
        result.put("kind", lower(variable.kind()));
        if (!variable.evaluateName().isBlank()) result.put("evaluate_name", variable.evaluateName());
        if (variable.namedVariables() > 0) result.put("named_count", variable.namedVariables());
        return result;
    }

    private static Map<String, Object> evaluation(DebugEngine.EvaluationResult value) {
        return value(value.value(), value.type(), value.variablesReference(), value.indexedVariables());
    }

    private static Map<String, Object> value(String value, String type, int reference, int indexed) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("value", value);
        result.put("type", type);
        if (reference > 0) result.put("value_ref", reference);
        if (indexed > 0) result.put("indexed_count", indexed);
        return result;
    }

    private static Map<String, Object> breakpoint(DebuggerSessionController.BreakpointEntry entry) {
        var breakpoint = entry.breakpoint();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("binary_name", entry.binaryName());
        result.put("line", breakpoint.line());
        result.put("state", lower(breakpoint.state()));
        if (breakpoint.resolvedLine() > 0) result.put("resolved_line", breakpoint.resolvedLine());
        if (breakpoint.request().condition() != null) result.put("condition", breakpoint.request().condition());
        if (breakpoint.request().hitCondition() != null) result.put("hit_condition", breakpoint.request().hitCondition());
        if (!breakpoint.detail().isBlank()) result.put("error", breakpoint.detail());
        return result;
    }

    private static String lower(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    private static String text(Map<String, Object> args, String name) {
        return (String) args.get(name);
    }

    private static int integer(Map<String, Object> args, String name, int defaultValue) {
        return args.containsKey(name) ? ((Number) args.get(name)).intValue() : defaultValue;
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.join();
        } catch (CompletionException exception) {
            if (exception.getCause() instanceof RuntimeException cause) throw cause;
            throw new IllegalStateException(exception.getCause().getMessage(), exception.getCause());
        }
    }
}
