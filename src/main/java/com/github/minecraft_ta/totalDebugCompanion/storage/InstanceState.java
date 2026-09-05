package com.github.minecraft_ta.totalDebugCompanion.storage;

import com.github.minecraft_ta.totalDebugCompanion.script.ExpressionHistory;
import com.github.minecraft_ta.totaldebug.storage.InstancePaths;
import com.github.minecraft_ta.totaldebug.storage.JsonFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** One instance's debugger intent and evaluator input recall. */
public final class InstanceState implements AutoCloseable {
    private List<String> debuggerWatches = List.of();
    private Map<String, List<PersistedBreakpoint>> debuggerBreakpoints = Map.of();
    private boolean debuggerBreakpointsMuted;
    private boolean breakOnCaughtExceptions;
    private boolean breakOnUncaughtExceptions;
    private List<ExpressionHistory.Entry> historyEntries = List.of();
    private ExpressionHistory history;
    private final JsonStateWriter writer;

    private InstanceState(JsonStateWriter writer) {
        this.writer = writer;
        initializeHistory();
    }

    public static InstanceState inMemory() {
        return new InstanceState(null);
    }

    public static InstanceState open(InstancePaths paths) throws IOException {
        InstanceState state = new InstanceState(new JsonStateWriter(paths.state()));
        if (!Files.exists(paths.state())) {
            return state;
        }
        try {
            var json = JsonFiles.read(paths.state());
            if (JsonFiles.integer(json, "format") != 2) {
                throw new IOException("Unsupported instance state format: " + paths.state());
            }
            JsonFiles.array(json, "debuggerWatches");
            JsonFiles.object(json, "debuggerBreakpoints");
            JsonFiles.array(json, "history");
            state.debuggerBreakpointsMuted = JsonFiles.bool(json, "debuggerBreakpointsMuted");
            state.breakOnCaughtExceptions = JsonFiles.bool(json, "breakOnCaughtExceptions");
            state.breakOnUncaughtExceptions = JsonFiles.bool(json, "breakOnUncaughtExceptions");
            Persisted data = JsonFiles.GSON.fromJson(json, Persisted.class);
            state.debuggerWatches = normalizeWatches(Objects.requireNonNull(data.debuggerWatches));
            Map<String, List<PersistedBreakpoint>> breakpoints = new HashMap<>();
            data.debuggerBreakpoints.forEach((key, value) -> {
                if (key.isBlank()) {
                    throw new IllegalArgumentException("Blank runtime signature in breakpoints");
                }
                breakpoints.put(key, List.copyOf(value));
            });
            state.debuggerBreakpoints = Map.copyOf(breakpoints);
            state.historyEntries = List.copyOf(data.history);
            state.initializeHistory();
            return state;
        } catch (IOException | RuntimeException exception) {
            state.writer.close();
            throw new IOException("Invalid instance state " + paths.state() + ": " + exception.getMessage(), exception);
        }
    }

    private void initializeHistory() {
        this.history = new ExpressionHistory(this.historyEntries, entries -> {
            synchronized (this) {
                this.historyEntries = entries;
                scheduleSave();
            }
        });
    }

    public ExpressionHistory expressionHistory() {
        return this.history;
    }

    public synchronized List<String> debuggerWatches() {
        return this.debuggerWatches;
    }

    public synchronized void setDebuggerWatches(List<String> expressions) {
        List<String> replacement = normalizeWatches(expressions);
        if (replacement.equals(this.debuggerWatches)) {
            return;
        }
        this.debuggerWatches = replacement;
        scheduleSave();
    }

    public synchronized List<PersistedBreakpoint> debuggerBreakpoints(String runtimeSignature) {
        if (runtimeSignature == null || runtimeSignature.isBlank()) {
            return List.of();
        }
        return this.debuggerBreakpoints.getOrDefault(runtimeSignature, List.of());
    }

    public synchronized void setDebuggerBreakpoints(String runtimeSignature, List<PersistedBreakpoint> breakpoints) {
        if (runtimeSignature == null || runtimeSignature.isBlank()) {
            throw new IllegalArgumentException("Runtime signature must not be blank");
        }
        List<PersistedBreakpoint> replacement = List.copyOf(Objects.requireNonNull(breakpoints, "breakpoints"));
        Map<String, List<PersistedBreakpoint>> updated = new HashMap<>(this.debuggerBreakpoints);
        if (replacement.isEmpty()) {
            updated.remove(runtimeSignature);
        } else {
            updated.put(runtimeSignature, replacement);
        }
        Map<String, List<PersistedBreakpoint>> immutable = Map.copyOf(updated);
        if (immutable.equals(this.debuggerBreakpoints)) {
            return;
        }
        this.debuggerBreakpoints = immutable;
        scheduleSave();
    }

    public synchronized boolean debuggerBreakpointsMuted() {
        return this.debuggerBreakpointsMuted;
    }

    public synchronized void setDebuggerBreakpointsMuted(boolean muted) {
        if (this.debuggerBreakpointsMuted == muted) {
            return;
        }
        this.debuggerBreakpointsMuted = muted;
        scheduleSave();
    }

    public synchronized boolean breakOnCaughtExceptions() {
        return this.breakOnCaughtExceptions;
    }

    public synchronized void setBreakOnCaughtExceptions(boolean enabled) {
        if (this.breakOnCaughtExceptions == enabled) {
            return;
        }
        this.breakOnCaughtExceptions = enabled;
        scheduleSave();
    }

    public synchronized boolean breakOnUncaughtExceptions() {
        return this.breakOnUncaughtExceptions;
    }

    public synchronized void setBreakOnUncaughtExceptions(boolean enabled) {
        if (this.breakOnUncaughtExceptions == enabled) {
            return;
        }
        this.breakOnUncaughtExceptions = enabled;
        scheduleSave();
    }


    private static List<String> normalizeWatches(List<String> expressions) {
        if (expressions == null || expressions.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String expression : expressions) {
            if (expression == null) {
                continue;
            }
            String trimmed = expression.trim();
            if (!trimmed.isEmpty()) {
                normalized.add(trimmed);
            }
        }
        return List.copyOf(normalized);
    }


    private void scheduleSave() {
        if (this.writer != null) {
            this.writer.schedule(JsonFiles.GSON.toJsonTree(new Persisted(2, this.debuggerWatches,
                    this.debuggerBreakpoints, this.debuggerBreakpointsMuted,
                    this.breakOnCaughtExceptions, this.breakOnUncaughtExceptions, this.historyEntries)));
        }
    }

    public void saveNow() throws IOException {
        if (this.writer != null) {
            this.writer.flush();
        }
    }

    @Override
    public void close() throws IOException {
        if (this.writer != null) {
            this.writer.close();
        }
    }

    private record Persisted(int format, List<String> debuggerWatches,
                             Map<String, List<PersistedBreakpoint>> debuggerBreakpoints,
                             boolean debuggerBreakpointsMuted, boolean breakOnCaughtExceptions,
                             boolean breakOnUncaughtExceptions, List<ExpressionHistory.Entry> history) { }

    public record PersistedBreakpoint(
            String sourceUri,
            String binaryName,
            int line,
            int debuggerLine,
            String methodOwner,
            String methodName,
            String methodDescriptor,
            String condition,
            String hitCondition,
            boolean enabled,
            com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine.BreakpointAction action
    ) {
        public PersistedBreakpoint(String sourceUri, String binaryName, int line, int debuggerLine,
                                   String methodOwner, String methodName, String methodDescriptor,
                                   String condition, String hitCondition, boolean enabled) {
            this(sourceUri, binaryName, line, debuggerLine, methodOwner, methodName, methodDescriptor,
                    condition, hitCondition, enabled, null);
        }
        public PersistedBreakpoint {
            if (sourceUri == null || sourceUri.isBlank()) {
                throw new IllegalArgumentException("Breakpoint source URI must not be blank");
            }
            if (binaryName == null || binaryName.isBlank()) {
                throw new IllegalArgumentException("Breakpoint binary name must not be blank");
            }
            if (line < 1 || debuggerLine < 0) {
                throw new IllegalArgumentException("Breakpoint lines are invalid");
            }
        }
    }


}
