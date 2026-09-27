package com.github.minecraft_ta.totalDebugCompanion.storage;

import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine;
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
import java.util.function.UnaryOperator;
import com.github.minecraft_ta.totalDebugCompanion.debugger.DebugEngine.BreakpointAction;

/** One instance's debugger intent, evaluator input recall, and the packs its resource edits are saved into. */
public final class InstanceState implements AutoCloseable {
    private List<String> debuggerWatches = List.of();
    private Map<String, List<PersistedBreakpoint>> debuggerBreakpoints = Map.of();
    private boolean debuggerBreakpointsMuted;
    private boolean breakOnCaughtExceptions;
    private boolean breakOnUncaughtExceptions;
    /** The folder name of the pack edits of each side are saved into, {@code assets} or {@code data}, where one was chosen. */
    private Map<String, String> workingPacks = Map.of();
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
            if (json.has("workingPacks")) {
                JsonFiles.object(json, "workingPacks").entrySet().forEach(entry -> {
                    if (!entry.getKey().equals("assets") && !entry.getKey().equals("data")) {
                        throw new IllegalArgumentException("Unknown side of working packs: " + entry.getKey());
                    }
                    JsonFiles.string(json.getAsJsonObject("workingPacks"), entry.getKey());
                });
                state.workingPacks = Map.copyOf(data.workingPacks);
            }
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

    public synchronized List<String> savedScriptReferences() {
        return debuggerBreakpoints.values().stream().flatMap(List::stream)
                .map(PersistedBreakpoint::action).filter(Objects::nonNull).map(BreakpointAction::script)
                .filter(Objects::nonNull).distinct().toList();
    }

    public synchronized void remapScriptActions(UnaryOperator<String> remap) {
        var updated = new HashMap<String, List<PersistedBreakpoint>>();
        debuggerBreakpoints.forEach((runtime, entries) -> updated.put(runtime, entries.stream().map(entry -> {
            var action = entry.action();
            if (action == null || action.script() == null) return entry;
            String script = remap.apply(action.script());
            return script.equals(action.script()) ? entry : new PersistedBreakpoint(entry.sourceUri(), entry.binaryName(),
                    entry.line(), entry.debuggerLine(), entry.methodOwner(), entry.methodName(), entry.methodDescriptor(),
                    entry.condition(), entry.hitCondition(), entry.enabled(), new BreakpointAction(null, script, action.continueOnSuccess()));
        }).toList()));
        if (!updated.equals(debuggerBreakpoints)) { debuggerBreakpoints = Map.copyOf(updated); scheduleSave(); }
    }

    /** The folder name of the pack edits of {@code side}, {@code assets} or {@code data}, are saved into, or empty. */
    public synchronized String workingPack(String side) {
        return this.workingPacks.getOrDefault(side, "");
    }

    /** Saves edits of {@code side} into the pack of folder {@code name}; empty goes back to the pack Companion manages. */
    public synchronized void setWorkingPack(String side, String name) {
        if (workingPack(side).equals(name)) return;
        Map<String, String> updated = new HashMap<>(this.workingPacks);
        if (name.isEmpty()) updated.remove(side);
        else updated.put(side, name);
        this.workingPacks = Map.copyOf(updated);
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
                    this.breakOnCaughtExceptions, this.breakOnUncaughtExceptions, this.historyEntries,
                    this.workingPacks.isEmpty() ? null : this.workingPacks)));
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
                             boolean breakOnUncaughtExceptions, List<ExpressionHistory.Entry> history,
                             Map<String, String> workingPacks) { }

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
            DebugEngine.BreakpointAction action
    ) {
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
