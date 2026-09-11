package com.github.minecraft_ta.totalDebugCompanion.mcp;

import com.github.minecraft_ta.totalDebugCompanion.CompanionApp;
import com.github.minecraft_ta.totalDebugCompanion.project.ProjectScope;

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

    Map<String, Object> call(String tool, Map<String, Object> args, ProjectScope project) throws IOException {
        DebuggerSessionController session = this.controller.get();
        try {
            return switch (tool) {
                case "debugger_status" -> sessionSnapshot(session);
                case "debugger_wait" -> {
                    session.waitForChange(((Number) args.get("after_revision")).longValue(), integer(args, "wait_ms", 30_000));
                    yield sessionSnapshot(session);
                }
                case "debugger_control" -> control(session, args, project);
                case "debugger_threads" -> Map.of("threads", await(session.threads()).stream()
                        .map(thread -> Map.of("id", thread.id(), "name", thread.name())).toList());
                case "debugger_breakpoints" -> Map.of(
                        "muted", session.breakpointsMuted(),
                        "breakpoints", session.breakpointEntries().stream().map(DebuggerMcpService::breakpoint).toList());
                case "debugger_breakpoint_set" -> setBreakpoint(session, args, project);
                case "debugger_breakpoint_remove" -> removeBreakpoint(session, args, project);
                case "debugger_frames" -> Map.of("frames", await(session.frames(text(args, "pause_id")))
                        .stream().map(DebuggerMcpService::frame).toList());
                case "debugger_variables" -> variables(session, args);
                case "debugger_evaluate" -> operation(session, await(project.admit(() -> session.startEvaluation(text(args, "pause_id"),
                        integer(args, "frame_id", 0), text(args, "source")))), integer(args, "wait_ms", 1000));
                case "debugger_evaluation_wait" -> operation(session,
                        await(session.evaluationOperation(text(args, "operation_id"))), integer(args, "wait_ms", 1000));
                case "debugger_evaluation_cancel" -> {
                    var requested = await(project.admit(() -> session.cancelEvaluation(text(args, "operation_id"))));
                    yield operation(session, requested, 0);
                }
                default -> throw new IllegalArgumentException("Unknown debugger tool: " + tool);
            };
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Debugger wait interrupted", exception);
        }
    }

    private static Map<String, Object> sessionSnapshot(DebuggerSessionController session) {
        Map<String, Object> result = snapshot(session.snapshot());
        var evaluation = session.evaluationStatus();
        if (evaluation != null) result.put("evaluation", operationStatus(evaluation));
        var action = session.breakpointActionResult();
        if (action != null) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("source", action.source());
            if (action.result() != null) details.put("result", evaluation(action.result()));
            if (action.error() != null) details.put("error", action.error());
            result.put("breakpoint_action", details);
        }
        return result;
    }

    private static Map<String, Object> operationStatus(
            com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerEvaluation.Snapshot state) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("operation_id", state.id());
        result.put("state", state.state());
        result.put("elapsed_ms", state.elapsedMillis());
        result.put("slow", state.slow());
        result.put("cancellation_requested", state.cancellationRequested());
        if (state.error() != null) result.put("error", state.error());
        return result;
    }

    private static Map<String, Object> operation(DebuggerSessionController session,
            com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerEvaluation<?> operation,
            int waitMillis) throws InterruptedException {
        if (waitMillis < 0 || waitMillis > 120_000) throw new IllegalArgumentException("wait_ms must be between 0 and 120000");
        Object value = null;
        try {
            value = operation.completion().get(waitMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException pending) {
            // Only the caller's wait expired. The same execution remains available by ID.
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.CancellationException failed) {
            // Terminal diagnostics belong to the operation response.
        }
        var state = operation.snapshot();
        if (value == null && state.state().equals("succeeded")) value = operation.completion().getNow(null);
        Map<String, Object> result = operationStatus(state);
        if (value instanceof DebugEngine.EvaluationResult) {
            var entry = await(session.evaluation(operation.id()));
            if (Objects.equals(session.snapshot().pauseId(), entry.pauseId())) {
                result.put("result", evaluation(await(session.evaluationResult(operation.id()))));
            } else {
                result.put("result_expired", true);
            }
        }
        return result;
    }

    private Map<String, Object> control(DebuggerSessionController session, Map<String, Object> args, ProjectScope project)
            throws InterruptedException {
        String action = text(args, "action");
        CompletableFuture<Void> operation = project.admit(() -> switch (action) {
            case "attach" -> session.attach();
            case "detach" -> session.detach();
            case "pause" -> session.pause(((Number) args.get("thread_id")).longValue());
            case "continue", "step_over", "step_into", "step_out" ->
                    session.controlPaused(text(args, "pause_id"), action);
            default -> throw new IllegalArgumentException("Unknown debugger action: " + action);
        });
        await(operation);
        int defaultWait = action.equals("pause") || action.startsWith("step_") ? 10_000 : 0;
        return snapshot(session.waitUntilStopped(integer(args, "wait_ms", defaultWait)));
    }

    private Map<String, Object> setBreakpoint(DebuggerSessionController session, Map<String, Object> args, ProjectScope project)
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
        project.requireActive();
        int line = integer(args, "line", 0);
        DebugEngine.SourceBreakpoint request = DebuggerBreakpointResolver.resolve(source, line,
                (String) args.get("condition"), (String) args.get("hit_condition"))
                .orElseThrow(() -> new IllegalArgumentException("No executable bytecode is mapped to line " + line));
        if (args.get("action") instanceof Map<?, ?> action) {
            request = request.withAction(new DebugEngine.BreakpointAction((String) action.get("source"),
                    (String) action.get("script"), "continue_on_success".equals(action.get("completion"))));
        }
        DebugEngine.SourceBreakpoint resolvedRequest = request;
        await(project.admit(() -> session.putBreakpoint(source, resolvedRequest, (Boolean) args.getOrDefault("enabled", true))));
        DebuggerSessionController.Breakpoint resolved = session.breakpoint(source.uri(), line);
        if (resolved == null) throw new IllegalStateException("Breakpoint was removed concurrently");
        return Map.of("breakpoint", breakpoint(new DebuggerSessionController.BreakpointEntry(
                source.uri(), source.binaryName(), resolved)));
    }

    private Map<String, Object> removeBreakpoint(DebuggerSessionController session, Map<String, Object> args, ProjectScope project) {
        String binaryName = text(args, "binary_name");
        int line = integer(args, "line", 0);
        List<DebuggerSessionController.BreakpointEntry> matches = session.breakpointEntries().stream()
                .filter(entry -> entry.binaryName().equals(binaryName) && entry.breakpoint().line() == line).toList();
        for (var entry : matches) await(project.admit(() -> session.removeBreakpoint(entry.sourceUri(), line)));
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
        Map<String, Object> result = value(value.value(), value.type(), value.variablesReference(), value.indexedVariables());
        if (value.scalar() != null) {
            Map<String, Object> scalar = new LinkedHashMap<>();
            scalar.put("kind", value.scalar().kind());
            scalar.put("value", value.scalar().value());
            result.put("scalar", scalar);
        }
        return result;
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
        var action = breakpoint.request().action();
        if (action != null) {
            Map<String, Object> details = new LinkedHashMap<>();
            if (action.source() != null) details.put("source", action.source());
            if (action.script() != null) details.put("script", action.script());
            details.put("completion", action.continueOnSuccess() ? "continue_on_success" : "stay_paused");
            result.put("action", details);
        }
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
