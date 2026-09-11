package com.github.minecraft_ta.totalDebugCompanion.debugger;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

public final class DebuggerSessionController implements AutoCloseable {
    private static final Duration ATTACH_TIMEOUT = Duration.ofSeconds(10);

    public enum Phase {
        UNAVAILABLE,
        DETACHED,
        ATTACHING,
        RUNNING,
        PAUSED,
        DETACHING,
        FAILED
    }

    public record Status(Phase phase, DebugTargetDescriptor target, String detail, Throwable failure) {
        public Status {
            Objects.requireNonNull(phase, "phase");
            detail = Objects.requireNonNullElse(detail, "");
        }
    }

    public record PausedState(
            DebugEngine.StoppedEvent event,
            List<DebugEngine.StackFrame> frames,
            List<DebugEngine.Variable> variables
    ) {
        public PausedState {
            Objects.requireNonNull(event, "event");
            frames = List.copyOf(frames);
            variables = List.copyOf(variables);
        }
    }

    public enum BreakpointState {
        DISABLED,
        UNBOUND,
        PENDING,
        BOUND,
        INVALID
    }

    public record Breakpoint(
            DebugEngine.SourceBreakpoint request,
            BreakpointState state,
            int resolvedLine,
            String detail
    ) {
        public Breakpoint {
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(state, "state");
            if (resolvedLine < 0) {
                throw new IllegalArgumentException("Resolved breakpoint line must not be negative");
            }
            detail = Objects.requireNonNullElse(detail, "");
        }

        public Breakpoint(DebugEngine.SourceBreakpoint request, BreakpointState state, String detail) {
            this(request, state, 0, detail);
        }

        public int line() {
            return this.request.line();
        }
    }

    /** Atomic externally observable state. Frame and value handles belong to one pause only. */
    public record Snapshot(long revision, Status status, String pauseId, PausedState pause) {
    }

    public record BreakpointDefinition(
            URI sourceUri,
            String binaryName,
            DebugEngine.SourceBreakpoint request,
            boolean enabled
    ) {
        public BreakpointDefinition {
            Objects.requireNonNull(sourceUri, "sourceUri");
            if (!sourceUri.isAbsolute()) {
                throw new IllegalArgumentException("Breakpoint source URI must be absolute");
            }
            if (binaryName == null || binaryName.isBlank()) {
                throw new IllegalArgumentException("Breakpoint binary name must not be blank");
            }
            Objects.requireNonNull(request, "request");
        }
    }

    public record BreakpointEntry(URI sourceUri, String binaryName, Breakpoint breakpoint) {
        public BreakpointEntry {
            Objects.requireNonNull(sourceUri, "sourceUri");
            Objects.requireNonNull(binaryName, "binaryName");
            Objects.requireNonNull(breakpoint, "breakpoint");
        }
    }

    public interface Listener {
        default void statusChanged(Status status) {
        }

        default void breakpointsChanged(URI sourceUri, List<Breakpoint> breakpoints) {
        }

        default void breakpointsMutedChanged(boolean muted) {
        }

        default void paused(PausedState state) {
        }
    }

    @FunctionalInterface
    interface TargetResolver {
        DebugEngine.Target resolve(DebugTargetDescriptor target, Duration timeout) throws Exception;
    }

    @FunctionalInterface
    public interface SourceLoader {
        DebugEngine.Source load(String binaryName) throws Exception;
    }

    private final Object modelLock = new Object();
    private final Supplier<DebugEngine> engineFactory;
    private final TargetResolver targetResolver;
    private final DebuggerSessionQueue queue = new DebuggerSessionQueue();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Map<URI, DebugEngine.Source> sources = new HashMap<>();
    private final Map<URI, NavigableMap<Integer, Breakpoint>> breakpoints = new HashMap<>();
    private final Map<URI, String> breakpointBinaryNames = new HashMap<>();
    private final Map<Integer, BreakpointKey> engineBreakpointIds = new HashMap<>();

    private volatile Status status = new Status(Phase.UNAVAILABLE, null, "No Minecraft debug target", null);
    private volatile DebugTargetDescriptor target;
    private volatile DebugEngine engine;
    private volatile PausedState pausedState;
    private final Object changeMonitor = new Object();
    private volatile Snapshot snapshot = new Snapshot(0, this.status, null, null);
    private String pauseId;
    private long pauseGeneration;
    private final Map<Integer, ExposedValue> exposedValues = new HashMap<>();

    private record ExposedValue(DebugEngine.StackFrame frame, boolean indexed) {
    }
    private volatile boolean breakOnCaughtExceptions;
    private volatile boolean breakOnUncaughtExceptions;
    private volatile boolean breakpointsMuted;
    private volatile boolean closed;
    private volatile DebugEngine.BreakpointActionResult breakpointActionResult;

    public DebugEngine.BreakpointActionResult breakpointActionResult() { return this.breakpointActionResult; }

    public DebuggerSessionController(SourceLoader sourceLoader) {
        this(sourceLoader, () -> null);
    }

    public DebuggerSessionController(SourceLoader sourceLoader, Supplier<String> classpath) {
        this(sourceLoader, classpath, path -> { throw new IllegalStateException("Saved breakpoint scripts require a script workspace"); });
    }

    public DebuggerSessionController(SourceLoader sourceLoader, Supplier<String> classpath,
                                    java.util.function.Function<String, String> scripts) {
        this(() -> {
            var engine = new MicrosoftJavaDebugEngine(Objects.requireNonNull(sourceLoader, "sourceLoader"), classpath);
            engine.breakpointScriptSource(scripts);
            return engine;
        }, new LocalJvmDebugTargetResolver()::resolve);
    }

    DebuggerSessionController(Supplier<DebugEngine> engineFactory, TargetResolver targetResolver) {
        this.engineFactory = Objects.requireNonNull(engineFactory, "engineFactory");
        this.targetResolver = Objects.requireNonNull(targetResolver, "targetResolver");
    }

    public Status status() {
        return this.status;
    }

    public PausedState pausedState() {
        return this.pausedState;
    }

    public Snapshot snapshot() {
        return this.snapshot;
    }

    /** Waits outside the debugger command queue so events and commands can still run. */
    public Snapshot waitForChange(long afterRevision, int waitMilliseconds) throws InterruptedException {
        checkWait(waitMilliseconds);
        synchronized (this.changeMonitor) {
            if (afterRevision < 0 || afterRevision > this.snapshot.revision()) {
                throw new IllegalArgumentException("after_revision does not belong to the current debugger session");
            }
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMilliseconds);
            while (!this.closed && this.snapshot.revision() == afterRevision) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                TimeUnit.NANOSECONDS.timedWait(this.changeMonitor, remaining);
            }
            return this.snapshot;
        }
    }

    public Snapshot waitUntilStopped(int waitMilliseconds) throws InterruptedException {
        checkWait(waitMilliseconds);
        synchronized (this.changeMonitor) {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMilliseconds);
            while (!this.closed && this.snapshot.status().phase() == Phase.RUNNING) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                TimeUnit.NANOSECONDS.timedWait(this.changeMonitor, remaining);
            }
            return this.snapshot;
        }
    }

    private static void checkWait(int milliseconds) {
        if (milliseconds < 0 || milliseconds > 120_000) {
            throw new IllegalArgumentException("wait_ms must be between 0 and 120000");
        }
    }

    public CompletableFuture<List<DebugEngine.DebugThread>> threads() {
        return submitValue(() -> requireEngine().threads().join());
    }

    public CompletableFuture<Void> pause(long threadId) {
        if (threadId <= 0) throw new IllegalArgumentException("thread_id must be positive");
        return submitFuture(() -> {
            if (this.status.phase() != Phase.RUNNING) {
                throw new IllegalStateException("Debugger pause requires state RUNNING");
            }
            this.queue.invalidateAdvisoryWork();
            requireEngine().pause(threadId).join();
        });
    }

    public CompletableFuture<Void> controlPaused(String expectedPauseId, String action) {
        Objects.requireNonNull(expectedPauseId, "pause_id");
        ThreadControl operation = switch (action) {
            case "continue" -> DebugEngine::resume;
            case "step_over" -> DebugEngine::stepOver;
            case "step_into" -> DebugEngine::stepInto;
            case "step_out" -> DebugEngine::stepOut;
            default -> throw new IllegalArgumentException("Unknown paused debugger action: " + action);
        };
        return control(action, expectedPauseId, operation);
    }

    public CompletableFuture<List<DebugEngine.StackFrame>> frames(String expectedPauseId) {
        return submitValue(() -> requireRemotePause(expectedPauseId).frames());
    }

    public CompletableFuture<List<DebugEngine.Variable>> variables(
            String expectedPauseId, Integer frameId, Integer valueReference, int start, int count
    ) {
        if ((frameId == null) == (valueReference == null)) {
            throw new IllegalArgumentException("Specify exactly one of frame_id and value_ref");
        }
        if (start < 0 || count < 1 || count > 501 || start > Integer.MAX_VALUE - count) {
            throw new IllegalArgumentException("Invalid debugger variable page");
        }
        return submitValue(() -> {
            requireRemotePause(expectedPauseId);
            requireEvaluationIdle();
            DebugEngine.StackFrame frame;
            List<DebugEngine.Variable> values;
            if (frameId != null) {
                frame = remoteFrame(expectedPauseId, frameId);
                List<DebugEngine.Variable> locals = loadVariables(requireEngine(), frame);
                int from = Math.min(start, locals.size());
                values = List.copyOf(locals.subList(from, Math.min(from + count, locals.size())));
            } else {
                ExposedValue exposed = this.exposedValues.get(valueReference);
                if (exposed == null) throw new IllegalArgumentException("Unknown value_ref for this pause");
                frame = exposed.frame();
                if (exposed.indexed()) {
                    values = requireEngine().variables(valueReference, start, count).join();
                } else {
                    List<DebugEngine.Variable> fields = requireEngine().variables(valueReference, 0, 0).join();
                    int from = Math.min(start, fields.size());
                    values = List.copyOf(fields.subList(from, Math.min(from + count, fields.size())));
                }
            }
            requireRemotePause(expectedPauseId);
            for (DebugEngine.Variable value : values) {
                exposeValue(value.variablesReference(), value.type(), frame);
            }
            return values;
        });
    }

    public CompletableFuture<DebugEngine.EvaluationResult> evaluate(
            String expectedPauseId, int frameId, String expression
    ) {
        return startEvaluation(expectedPauseId, frameId, expression)
                .thenCompose(operation -> operation.completion().thenCompose(result -> submitValue(() -> {
                    DebugEngine.StackFrame frame = remoteFrame(expectedPauseId, frameId);
                    exposeValue(result.variablesReference(), result.type(), frame);
                    return result;
                })));
    }

    private final Map<String, EvaluationEntry> evaluations = new java.util.LinkedHashMap<>();
    private final Map<String, DebuggerValueLease> evaluationValues = new java.util.HashMap<>();
    public record EvaluationEntry(String pauseId, DebugEngine.StackFrame frame,
                                  DebuggerEvaluation<DebugEngine.EvaluationResult> operation) { }

    public CompletableFuture<DebuggerEvaluation<DebugEngine.EvaluationResult>> startEvaluation(
            String expectedPauseId, int frameId, String source) {
        return submitValue(() -> {
            DebugEngine.StackFrame frame = remoteFrame(expectedPauseId, frameId);
            requireEvaluationIdle();
            DebugEngine owner = requireEngine();
            var operation = owner.startEvaluation(source, frameId);
            this.evaluations.put(operation.id(), new EvaluationEntry(expectedPauseId, frame, operation));
            while (this.evaluations.size() > 128) {
                String expired = this.evaluations.entrySet().stream().filter(e -> !e.getValue().operation().running())
                        .map(Map.Entry::getKey).findFirst().orElse(null);
                if (expired == null) break;
                this.evaluations.remove(expired);
                DebuggerValueLease retained = this.evaluationValues.remove(expired);
                if (retained != null) retained.close();
            }
            operation.completion().whenComplete((result, failure) -> {
                DebuggerValueLease value = DebuggerValueLease.NONE;
                if (result != null && result.variablesReference() > 0) {
                    try { value = owner.retainValue(result.variablesReference()); }
                    catch (IllegalStateException | IllegalArgumentException expired) {
                        // Resume or detach can expire the value before this completion observer runs.
                    }
                }
                DebuggerValueLease retained = value;
                submitFuture(() -> {
                    if (this.engine == owner && this.evaluations.containsKey(operation.id())
                            && Objects.equals(this.pauseId, expectedPauseId)) {
                        this.evaluationValues.put(operation.id(), retained);
                    } else retained.close();
                    if (result != null && Objects.equals(this.pauseId, expectedPauseId)
                            && this.pauseGeneration == this.queue.advisoryGeneration()) {
                        exposeValue(result.variablesReference(), result.type(), frame);
                    }
                    publishRevision();
                }).whenComplete((ignored, observerFailure) -> {
                    if (observerFailure != null) retained.close();
                });
            });
            publishRevision();
            return operation;
        });
    }

    public CompletableFuture<EvaluationEntry> evaluation(String id) {
        return submitValue(() -> {
            EvaluationEntry entry = this.evaluations.get(id);
            if (entry == null) throw new IllegalArgumentException("Unknown or expired evaluation ID");
            return entry;
        });
    }

    public CompletableFuture<DebuggerValueLease> retainValue(String expectedPauseId, int reference) {
        return submitValue(() -> {
            requireRemotePause(expectedPauseId);
            return requireEngine().retainValue(reference);
        });
    }

    public CompletableFuture<DebugEngine.EvaluationResult> evaluationResult(String id) {
        return submitValue(() -> {
            EvaluationEntry entry = this.evaluations.get(id);
            if (entry == null) throw new IllegalArgumentException("Unknown or expired evaluation ID");
            requireRemotePause(entry.pauseId());
            DebugEngine.EvaluationResult result = entry.operation().completion().getNow(null);
            if (result != null) exposeValue(result.variablesReference(), result.type(), entry.frame());
            return result;
        });
    }

    public CompletableFuture<DebuggerEvaluation<?>> evaluationOperation(String id) {
        return submitValue(() -> {
            EvaluationEntry entry = this.evaluations.get(id);
            if (entry != null) return entry.operation();
            DebugEngine current = this.engine;
            DebuggerEvaluation<?> operation = current == null ? null : current.evaluationOperation(id);
            if (operation == null) throw new IllegalArgumentException("Unknown or expired evaluation ID");
            return operation;
        });
    }

    public CompletableFuture<DebuggerEvaluation<?>> cancelEvaluation(String id) {
        return evaluationOperation(id).thenApply(operation -> {
            operation.cancel();
            publishRevision();
            return operation;
        });
    }

    public DebuggerEvaluation.Snapshot evaluationStatus() {
        DebugEngine current = this.engine;
        var active = current == null ? null : current.activeEvaluation();
        return active == null ? null : active.snapshot();
    }

    public void cancelActiveEvaluation() {
        DebugEngine current = this.engine;
        var active = current == null ? null : current.activeEvaluation();
        if (active != null) active.cancel();
        publishRevision();
    }

    private void requireEvaluationIdle() {
        DebugEngine current = this.engine;
        if (current != null && current.activeEvaluation() != null) {
            throw new IllegalStateException("Debugger evaluation is still running; wait or request cancellation");
        }
    }

    private void publishRevision() {
        synchronized (this.changeMonitor) {
            Snapshot previous = this.snapshot;
            this.snapshot = new Snapshot(previous.revision() + 1, previous.status(), previous.pauseId(), previous.pause());
            this.changeMonitor.notifyAll();
        }
        this.listeners.forEach(listener -> listener.statusChanged(this.status));
    }

    private void exposeValue(int reference, String type, DebugEngine.StackFrame frame) {
        if (reference > 0) this.exposedValues.put(reference, new ExposedValue(frame, type.endsWith("[]")));
    }

    private DebugEngine.StackFrame remoteFrame(String expectedPauseId, int frameId) {
        return requireRemotePause(expectedPauseId).frames().stream()
                .filter(frame -> frame.id() == frameId).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown frame_id for this pause"));
    }

    private PausedState requireRemotePause(String expectedPauseId) {
        PausedState pause = requirePausedState();
        if (!Objects.equals(this.pauseId, expectedPauseId)
                || this.pauseGeneration != this.queue.advisoryGeneration()) {
            throw new IllegalStateException("pause_id is stale; read debugger_status for the current pause");
        }
        return pause;
    }

    public void addListener(Listener listener) {
        Listener checked = Objects.requireNonNull(listener, "listener");
        this.listeners.add(checked);
        checked.statusChanged(this.status);
        PausedState currentPause = this.pausedState;
        if (currentPause != null) {
            checked.paused(currentPause);
        }
        checked.breakpointsMutedChanged(this.breakpointsMuted);
    }

    public void removeListener(Listener listener) {
        this.listeners.remove(listener);
    }

    public void acceptTarget(DebugTargetDescriptor replacement) {
        Objects.requireNonNull(replacement, "replacement");
        this.queue.invalidateAdvisoryWork();
        submit(() -> {
            closeEngine();
            this.target = replacement;
            this.pausedState = null;
            updateStatus(Phase.DETACHED, replacement.displayName() + " is ready to attach", null);
        });
    }

    public CompletableFuture<Void> clearTarget() {
        this.queue.invalidateAdvisoryWork();
        return submitFuture(() -> {
            DebugTargetDescriptor previous = this.target;
            this.target = null;
            if (this.engine != null) {
                updateStatus(Phase.DETACHING, "Detaching from " + previous.displayName(), null);
            }
            closeEngine();
            this.pausedState = null;
            updateStatus(Phase.UNAVAILABLE, "No Minecraft debug target", null);
        });
    }

    public CompletableFuture<Void> attach() {
        return submitFuture(() -> {
            DebugTargetDescriptor currentTarget = requireTarget();
            Phase phase = this.status.phase();
            if (phase != Phase.DETACHED && phase != Phase.FAILED) {
                throw new IllegalStateException("Debugger attach requires state DETACHED or FAILED, current state is "
                        + phase);
            }
            this.queue.invalidateAdvisoryWork();
            if (this.engine != null) {
                closeEngine();
            }
            updateStatus(Phase.ATTACHING, "Attaching to " + currentTarget.displayName(), null);

            DebugEngine replacement = this.engineFactory.get();
            this.engine = replacement;
            replacement.addListener(engineListener(replacement));
            try {
                for (DebugEngine.Source source : sourcesSnapshot()) {
                    replacement.registerSource(source);
                }
                DebugEngine.Target endpoint = this.targetResolver.resolve(currentTarget, ATTACH_TIMEOUT);
                replacement.attach(endpoint).join();
                applyAllBreakpoints(replacement);
                replacement.setExceptionBreakpoints(
                        this.breakOnCaughtExceptions,
                        this.breakOnUncaughtExceptions
                ).join();
                replacement.start().join();
                updateStatus(Phase.RUNNING, "Attached to " + currentTarget.displayName(), null);
            } catch (Throwable failure) {
                Throwable cause = unwrap(failure);
                closeEngine();
                updateStatus(Phase.FAILED, failureMessage(cause), cause);
                throw propagate(cause);
            }
        });
    }

    public CompletableFuture<Void> detach() {
        this.queue.invalidateAdvisoryWork();
        return submitFuture(() -> {
            DebugTargetDescriptor currentTarget = this.target;
            if (this.engine == null) {
                if (currentTarget != null) {
                    updateStatus(Phase.DETACHED, currentTarget.displayName() + " is ready to attach", null);
                }
                return;
            }
            updateStatus(
                    Phase.DETACHING,
                    currentTarget == null ? "Detaching debugger" : "Detaching from " + currentTarget.displayName(),
                    null
            );
            closeEngine();
            this.pausedState = null;
            if (currentTarget == null) {
                updateStatus(Phase.UNAVAILABLE, "No Minecraft debug target", null);
            } else {
                updateStatus(Phase.DETACHED, currentTarget.displayName() + " is ready to attach", null);
            }
        });
    }

    public void registerSource(DebugEngine.Source source) {
        DebugEngine.Source checked = Objects.requireNonNull(source, "source");
        SourceRegistration registration = registerSourceModel(checked);
        if (!registration.changed()) {
            return;
        }
        if (registration.breakpoints() != null) {
            notifyBreakpointsChanged(checked.uri(), registration.breakpoints());
        }
        submit(() -> {
            DebugEngine current = this.engine;
            if (current != null) {
                current.registerSource(checked);
                if (registration.hasBreakpoints()) {
                    applyBreakpoints(current, checked.uri());
                }
            }
        });
    }

    private SourceRegistration registerSourceModel(DebugEngine.Source source) {
        synchronized (this.modelLock) {
            DebugEngine.Source previous = this.sources.put(source.uri(), source);
            if (source.equals(previous)) {
                return SourceRegistration.UNCHANGED;
            }
            boolean hasBreakpoints = this.breakpoints.containsKey(source.uri());
            if (hasBreakpoints) {
                this.breakpointBinaryNames.put(source.uri(), source.binaryName());
            }
            return new SourceRegistration(
                    true,
                    hasBreakpoints,
                    revalidateBreakpointsLocked(source)
            );
        }
    }

    public DebugEngine.Source source(URI sourceUri) {
        synchronized (this.modelLock) {
            return this.sources.get(sourceUri);
        }
    }

    public List<Breakpoint> breakpoints(URI sourceUri) {
        synchronized (this.modelLock) {
            NavigableMap<Integer, Breakpoint> sourceBreakpoints = this.breakpoints.get(sourceUri);
            return sourceBreakpoints == null ? List.of() : List.copyOf(sourceBreakpoints.values());
        }
    }

    public List<BreakpointEntry> breakpointEntries() {
        synchronized (this.modelLock) {
            List<BreakpointEntry> result = new ArrayList<>();
            for (Map.Entry<URI, NavigableMap<Integer, Breakpoint>> sourceEntry : this.breakpoints.entrySet()) {
                String binaryName = this.breakpointBinaryNames.get(sourceEntry.getKey());
                if (binaryName == null) {
                    throw new IllegalStateException("Breakpoint source has no runtime binary name: "
                            + sourceEntry.getKey());
                }
                for (Breakpoint breakpoint : sourceEntry.getValue().values()) {
                    result.add(new BreakpointEntry(sourceEntry.getKey(), binaryName, breakpoint));
                }
            }
            result.sort(Comparator.comparing(BreakpointEntry::binaryName)
                    .thenComparingInt(entry -> entry.breakpoint().line()));
            return List.copyOf(result);
        }
    }

    public List<BreakpointDefinition> breakpointDefinitions() {
        return breakpointEntries().stream()
                .map(entry -> new BreakpointDefinition(
                        entry.sourceUri(),
                        entry.binaryName(),
                        entry.breakpoint().request(),
                        entry.breakpoint().state() != BreakpointState.DISABLED
                ))
                .toList();
    }

    public boolean breakpointsMuted() {
        return this.breakpointsMuted;
    }

    public CompletableFuture<Void> setBreakpointsMuted(boolean muted) {
        if (this.breakpointsMuted == muted) {
            return CompletableFuture.completedFuture(null);
        }
        Map<URI, List<Breakpoint>> changed = new HashMap<>();
        synchronized (this.modelLock) {
            this.breakpointsMuted = muted;
            if (muted) {
                this.engineBreakpointIds.clear();
                for (Map.Entry<URI, NavigableMap<Integer, Breakpoint>> sourceEntry : this.breakpoints.entrySet()) {
                    boolean sourceChanged = false;
                    for (Map.Entry<Integer, Breakpoint> breakpointEntry : sourceEntry.getValue().entrySet()) {
                        Breakpoint breakpoint = breakpointEntry.getValue();
                        if (breakpoint.state() == BreakpointState.BOUND
                                || breakpoint.state() == BreakpointState.PENDING) {
                            breakpointEntry.setValue(new Breakpoint(
                                    breakpoint.request(),
                                    BreakpointState.UNBOUND,
                                    ""
                            ));
                            sourceChanged = true;
                        }
                    }
                    if (sourceChanged) {
                        changed.put(sourceEntry.getKey(), List.copyOf(sourceEntry.getValue().values()));
                    }
                }
            }
        }
        for (Listener listener : this.listeners) {
            listener.breakpointsMutedChanged(muted);
        }
        advanceRevision();
        changed.forEach(this::notifyBreakpointsChanged);
        return submitFuture(() -> {
            DebugEngine current = this.engine;
            if (current != null) {
                applyAllBreakpoints(current);
            }
        });
    }

    public CompletableFuture<Void> replaceBreakpointDefinitions(List<BreakpointDefinition> definitions) {
        List<BreakpointDefinition> checked = List.copyOf(Objects.requireNonNull(definitions, "definitions"));
        LinkedHashSet<URI> changedUris;
        synchronized (this.modelLock) {
            changedUris = new LinkedHashSet<>(this.breakpoints.keySet());
            this.sources.clear();
            this.breakpoints.clear();
            this.breakpointBinaryNames.clear();
            this.engineBreakpointIds.clear();
            for (BreakpointDefinition definition : checked) {
                NavigableMap<Integer, Breakpoint> sourceBreakpoints =
                        this.breakpoints.computeIfAbsent(definition.sourceUri(), ignored -> new TreeMap<>());
                if (sourceBreakpoints.containsKey(definition.request().line())) {
                    throw new IllegalArgumentException(
                            "Duplicate breakpoint at " + definition.sourceUri() + ":" + definition.request().line()
                    );
                }
                DebugEngine.Source source = this.sources.get(definition.sourceUri());
                sourceBreakpoints.put(
                        definition.request().line(),
                        definition.enabled()
                                ? createdBreakpoint(source, definition.request())
                                : new Breakpoint(definition.request(), BreakpointState.DISABLED, "")
                );
                this.breakpointBinaryNames.put(definition.sourceUri(), definition.binaryName());
                changedUris.add(definition.sourceUri());
            }
        }
        for (URI sourceUri : changedUris) {
            notifyBreakpointsChanged(sourceUri, breakpoints(sourceUri));
        }
        return submitFuture(() -> {
            DebugEngine current = this.engine;
            if (current != null) {
                for (URI sourceUri : changedUris) {
                    if (source(sourceUri) == null) {
                        current.setBreakpoints(sourceUri, List.of()).join();
                    } else {
                        applyBreakpoints(current, sourceUri);
                    }
                }
            }
        });
    }

    public CompletableFuture<Void> setExceptionBreakpoints(boolean caught, boolean uncaught) {
        return submitFuture(() -> {
            this.breakOnCaughtExceptions = caught;
            this.breakOnUncaughtExceptions = uncaught;
            DebugEngine current = this.engine;
            if (current != null) {
                current.setExceptionBreakpoints(caught, uncaught).join();
            }
        });
    }

    public Breakpoint breakpoint(URI sourceUri, int line) {
        synchronized (this.modelLock) {
            NavigableMap<Integer, Breakpoint> sourceBreakpoints = this.breakpoints.get(sourceUri);
            return sourceBreakpoints == null ? null : sourceBreakpoints.get(line);
        }
    }

    public CompletableFuture<Boolean> toggleBreakpoint(DebugEngine.Source source, int line) {
        return toggleBreakpoint(source, new DebugEngine.SourceBreakpoint(line));
    }

    public CompletableFuture<Boolean> toggleBreakpoint(
            DebugEngine.Source source,
            DebugEngine.SourceBreakpoint request
    ) {
        DebugEngine.Source checkedSource = Objects.requireNonNull(source, "source");
        DebugEngine.SourceBreakpoint checkedRequest = Objects.requireNonNull(request, "request");
        SourceRegistration registration = registerSourceModel(checkedSource);
        boolean enabled;
        List<Breakpoint> snapshot;
        synchronized (this.modelLock) {
            NavigableMap<Integer, Breakpoint> sourceBreakpoints =
                    this.breakpoints.computeIfAbsent(checkedSource.uri(), ignored -> new TreeMap<>());
            this.breakpointBinaryNames.put(checkedSource.uri(), checkedSource.binaryName());
            if (sourceBreakpoints.remove(checkedRequest.line()) != null) {
                removeBreakpointBindingLocked(checkedSource.uri(), checkedRequest.line());
                enabled = false;
            } else {
                sourceBreakpoints.put(
                        checkedRequest.line(),
                        createdBreakpoint(checkedSource, checkedRequest)
                );
                enabled = true;
            }
            if (sourceBreakpoints.isEmpty()) {
                this.breakpoints.remove(checkedSource.uri());
                this.breakpointBinaryNames.remove(checkedSource.uri());
            }
            snapshot = List.copyOf(sourceBreakpoints.values());
        }
        notifyBreakpointsChanged(checkedSource.uri(), snapshot);
        return applySourceBreakpointMutation(checkedSource, registration, enabled);
    }

    public CompletableFuture<Boolean> toggleBreakpointEnabled(DebugEngine.Source source, int line) {
        DebugEngine.Source checkedSource = Objects.requireNonNull(source, "source");
        SourceRegistration registration = registerSourceModel(checkedSource);
        boolean enabled;
        List<Breakpoint> snapshot;
        synchronized (this.modelLock) {
            NavigableMap<Integer, Breakpoint> sourceBreakpoints = this.breakpoints.get(checkedSource.uri());
            Breakpoint existing = sourceBreakpoints == null ? null : sourceBreakpoints.get(line);
            if (existing == null) {
                return CompletableFuture.failedFuture(
                        new IllegalArgumentException("No breakpoint exists at line " + line)
                );
            }
            enabled = existing.state() == BreakpointState.DISABLED;
            removeBreakpointBindingLocked(checkedSource.uri(), line);
            sourceBreakpoints.put(
                    line,
                    enabled
                            ? createdBreakpoint(checkedSource, existing.request())
                            : new Breakpoint(existing.request(), BreakpointState.DISABLED, "")
            );
            snapshot = List.copyOf(sourceBreakpoints.values());
        }
        notifyBreakpointsChanged(checkedSource.uri(), snapshot);
        return applySourceBreakpointMutation(checkedSource, registration, enabled);
    }

    private <T> CompletableFuture<T> applySourceBreakpointMutation(
            DebugEngine.Source source,
            SourceRegistration registration,
            T result
    ) {
        return submitFuture(() -> {
            DebugEngine current = this.engine;
            if (current != null) {
                if (registration.changed()) {
                    current.registerSource(source);
                }
                applyBreakpoints(current, source.uri());
            }
        }).thenApply(ignored -> result);
    }

    public CompletableFuture<Boolean> setBreakpointEnabled(URI sourceUri, int line, boolean enabled) {
        URI checkedUri = Objects.requireNonNull(sourceUri, "sourceUri");
        List<Breakpoint> snapshot;
        synchronized (this.modelLock) {
            NavigableMap<Integer, Breakpoint> sourceBreakpoints = this.breakpoints.get(checkedUri);
            Breakpoint current = sourceBreakpoints == null ? null : sourceBreakpoints.get(line);
            if (current == null) {
                return CompletableFuture.failedFuture(
                        new IllegalArgumentException("No breakpoint exists at line " + line)
                );
            }
            removeBreakpointBindingLocked(checkedUri, line);
            DebugEngine.Source registeredSource = this.sources.get(checkedUri);
            sourceBreakpoints.put(
                    line,
                    enabled
                            ? createdBreakpoint(registeredSource, current.request())
                            : new Breakpoint(current.request(), BreakpointState.DISABLED, "")
            );
            snapshot = List.copyOf(sourceBreakpoints.values());
        }
        notifyBreakpointsChanged(checkedUri, snapshot);
        return submitFuture(() -> {
            DebugEngine current = this.engine;
            if (current != null) {
                applyBreakpoints(current, checkedUri);
            }
        }).thenApply(ignored -> enabled);
    }

    public CompletableFuture<Void> removeBreakpoint(URI sourceUri, int line) {
        URI checkedUri = Objects.requireNonNull(sourceUri, "sourceUri");
        List<Breakpoint> snapshot;
        synchronized (this.modelLock) {
            NavigableMap<Integer, Breakpoint> sourceBreakpoints = this.breakpoints.get(checkedUri);
            if (sourceBreakpoints == null || sourceBreakpoints.remove(line) == null) {
                return CompletableFuture.completedFuture(null);
            }
            removeBreakpointBindingLocked(checkedUri, line);
            if (sourceBreakpoints.isEmpty()) {
                this.breakpoints.remove(checkedUri);
                this.breakpointBinaryNames.remove(checkedUri);
                snapshot = List.of();
            } else {
                snapshot = List.copyOf(sourceBreakpoints.values());
            }
        }
        notifyBreakpointsChanged(checkedUri, snapshot);
        return submitFuture(() -> {
            DebugEngine current = this.engine;
            if (current != null) {
                applyBreakpoints(current, checkedUri);
            }
        });
    }

    public CompletableFuture<Void> configureBreakpoint(
            DebugEngine.Source source,
            int line,
            String condition,
            String hitCondition
    ) {
        DebugEngine.Source checkedSource = Objects.requireNonNull(source, "source");
        DebugEngine.SourceBreakpoint request;
        synchronized (this.modelLock) {
            NavigableMap<Integer, Breakpoint> sourceBreakpoints = this.breakpoints.get(checkedSource.uri());
            Breakpoint existing = sourceBreakpoints == null ? null : sourceBreakpoints.get(line);
            request = existing == null
                    ? new DebugEngine.SourceBreakpoint(line, condition, hitCondition)
                    : existing.request().withConditions(condition, hitCondition);
        }
        return configureBreakpoint(checkedSource, request);
    }

    public CompletableFuture<Void> configureBreakpoint(
            DebugEngine.Source source,
            DebugEngine.SourceBreakpoint request
    ) {
        DebugEngine.Source checkedSource = Objects.requireNonNull(source, "source");
        DebugEngine.SourceBreakpoint checkedRequest = Objects.requireNonNull(request, "request");
        SourceRegistration registration = registerSourceModel(checkedSource);
        return configureBreakpoint(checkedSource.uri(), checkedRequest, checkedSource, registration);
    }

    /** Deterministic create/update, including explicit enabled state. */
    public CompletableFuture<Void> putBreakpoint(
            DebugEngine.Source source, DebugEngine.SourceBreakpoint request, boolean enabled
    ) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(request, "request");
        SourceRegistration registration = registerSourceModel(source);
        DebugEngine.SourceBreakpoint normalized = request.withConditions(
                normalizeExpression(request.condition()), normalizeExpression(request.hitCondition()));
        List<Breakpoint> changed;
        synchronized (this.modelLock) {
            NavigableMap<Integer, Breakpoint> entries = this.breakpoints.computeIfAbsent(
                    source.uri(), ignored -> new TreeMap<>());
            this.breakpointBinaryNames.put(source.uri(), source.binaryName());
            removeBreakpointBindingLocked(source.uri(), request.line());
            entries.put(request.line(), enabled ? createdBreakpoint(source, normalized)
                    : new Breakpoint(normalized, BreakpointState.DISABLED, ""));
            changed = List.copyOf(entries.values());
        }
        notifyBreakpointsChanged(source.uri(), changed);
        return applySourceBreakpointMutation(source, registration, null);
    }

    public CompletableFuture<Void> configureBreakpoint(
            URI sourceUri,
            DebugEngine.SourceBreakpoint request
    ) {
        return configureBreakpoint(sourceUri, request, null, SourceRegistration.UNCHANGED);
    }

    private CompletableFuture<Void> configureBreakpoint(
            URI sourceUri,
            DebugEngine.SourceBreakpoint request,
            DebugEngine.Source sourceRegisteredByMutation,
            SourceRegistration registration
    ) {
        URI checkedUri = Objects.requireNonNull(sourceUri, "sourceUri");
        DebugEngine.SourceBreakpoint checkedRequest = Objects.requireNonNull(request, "request");
        checkedRequest = checkedRequest.withConditions(
                normalizeExpression(checkedRequest.condition()),
                normalizeExpression(checkedRequest.hitCondition())
        );
        List<Breakpoint> snapshot;
        synchronized (this.modelLock) {
            NavigableMap<Integer, Breakpoint> sourceBreakpoints = this.breakpoints.get(checkedUri);
            Breakpoint existing = sourceBreakpoints == null ? null : sourceBreakpoints.get(checkedRequest.line());
            DebugEngine.Source registeredSource = this.sources.get(checkedUri);
            if (registeredSource != null) {
                this.breakpointBinaryNames.put(checkedUri, registeredSource.binaryName());
            } else if (existing == null) {
                throw new IllegalArgumentException("No breakpoint exists at line " + checkedRequest.line());
            }
            if (sourceBreakpoints == null) {
                sourceBreakpoints = new TreeMap<>();
                this.breakpoints.put(checkedUri, sourceBreakpoints);
            }
            removeBreakpointBindingLocked(checkedUri, checkedRequest.line());
            sourceBreakpoints.put(
                    checkedRequest.line(),
                    existing != null && existing.state() == BreakpointState.DISABLED
                            ? new Breakpoint(checkedRequest, BreakpointState.DISABLED, "")
                            : createdBreakpoint(registeredSource, checkedRequest)
            );
            snapshot = List.copyOf(sourceBreakpoints.values());
        }
        notifyBreakpointsChanged(checkedUri, snapshot);
        return submitFuture(() -> {
            DebugEngine current = this.engine;
            if (current != null) {
                if (registration.changed()) {
                    current.registerSource(sourceRegisteredByMutation);
                }
                applyBreakpoints(current, checkedUri);
            }
        });
    }

    public CompletableFuture<Void> resume() {
        return control("Resuming", DebugEngine::resume);
    }

    public CompletableFuture<Void> stepOver() {
        return control("Stepping over", DebugEngine::stepOver);
    }

    public CompletableFuture<Void> stepInto() {
        return control("Stepping into", DebugEngine::stepInto);
    }

    public CompletableFuture<Void> stepOut() {
        return control("Stepping out", DebugEngine::stepOut);
    }

    public CompletableFuture<List<DebugEngine.Variable>> variablesForFrame(DebugEngine.StackFrame frame) {
        Objects.requireNonNull(frame, "frame");
        return submitAdvisory(frame, () -> loadVariables(requireEngine(), frame));
    }

    public CompletableFuture<List<DebugEngine.Variable>> variables(
            DebugEngine.StackFrame frame,
            int variablesReference,
            int start,
            int count
    ) {
        return submitAdvisory(frame, () ->
                requireEngine().variables(variablesReference, start, count).join());
    }

    public CompletableFuture<List<DebugEngine.Variable>> setVariable(
            DebugEngine.Variable variable,
            String value,
            DebugEngine.StackFrame frame
    ) {
        Objects.requireNonNull(variable, "variable");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(frame, "frame");
        if (variable.containerReference() <= 0 || variable.adapterName().isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "Selected debugger value cannot be assigned"
            ));
        }
        return submitValue(() -> {
            requirePausedFrame(frame);
            DebugEngine current = requireEngine();
            current.setVariable(variable.containerReference(), variable.adapterName(), value).join();
            return loadVariables(current, frame);
        });
    }

    public CompletableFuture<DebugEngine.ValuePreview> preview(
            DebugEngine.StackFrame frame,
            int variablesReference
    ) {
        if (variablesReference <= 0) {
            return CompletableFuture.completedFuture(DebugEngine.ValuePreview.NONE);
        }
        long generation = this.queue.advisoryGeneration();
        return submitValue(() -> {
            requirePausedFrame(frame);
            requireEvaluationIdle();
            return requireEngine().preview(variablesReference);
        }).thenCompose(future -> future).thenCompose(result -> submitValue(() -> {
            if (generation != this.queue.advisoryGeneration()) throw new IllegalStateException("Preview frame is stale");
            requirePausedFrame(frame);
            return result;
        }));
    }

    public CompletableFuture<DebugEngine.EvaluationResult> evaluate(String expression, DebugEngine.StackFrame frame) {
        return submitValue(() -> {
            requirePausedFrame(frame);
            return this.pauseId;
        }).thenCompose(id -> evaluate(id, frame.id(), expression));
    }

    public CompletableFuture<DebugEngine.EvaluationResult> inspectExpression(String expression, DebugEngine.StackFrame frame) {
        return evaluate(expression, frame);
    }

    public CompletableFuture<List<DebuggerCompletionProposal>> completions(
            String expression,
            int caret,
            DebugEngine.StackFrame frame
    ) {
        Objects.requireNonNull(frame, "frame");
        return submitAdvisory(frame, () -> requireEngine().completions(expression, caret, frame.id()).join());
    }

    public CompletableFuture<List<DebugEngine.ExpressionToken>> expressionTokens(
            String expression,
            DebugEngine.StackFrame frame
    ) {
        Objects.requireNonNull(frame, "frame");
        return submitAdvisory(frame, () -> requireEngine().expressionTokens(expression, frame.id()).join());
    }

    private CompletableFuture<Void> control(String detail, ThreadControl control) {
        return control(detail, null, control);
    }

    private CompletableFuture<Void> control(String detail, String expectedPauseId, ThreadControl control) {
        try { requireEvaluationIdle(); }
        catch (RuntimeException busy) { return CompletableFuture.failedFuture(busy); }
        if (expectedPauseId == null) this.queue.invalidateAdvisoryWork();
        return submitFuture(() -> {
            requireEvaluationIdle();
            DebugEngine current = requireEngine();
            PausedState pause = this.pausedState;
            if (this.status.phase() != Phase.PAUSED || pause == null) {
                throw new IllegalStateException("Debugger control requires state PAUSED");
            }
            if (expectedPauseId != null) {
                requireRemotePause(expectedPauseId);
                this.queue.invalidateAdvisoryWork();
            }
            this.pausedState = null;
            this.breakpointActionResult = null;
            updateStatus(Phase.RUNNING, detail, null);
            try {
                control.apply(current, pause.event().threadId()).join();
                if (this.engine == current && this.pausedState == null
                        && this.status.phase() == Phase.RUNNING) {
                    DebugTargetDescriptor currentTarget = this.target;
                    updateStatus(
                            Phase.RUNNING,
                            currentTarget == null
                                    ? "Debugger running"
                                    : "Attached to " + currentTarget.displayName(),
                            null
                    );
                }
            } catch (Throwable failure) {
                Throwable cause = unwrap(failure);
                this.pausedState = pause;
                this.pauseGeneration = this.queue.advisoryGeneration();
                updateStatus(Phase.PAUSED, detail + " failed: " + failureMessage(cause), cause);
                throw propagate(cause);
            }
        });
    }

    private DebugEngine.Listener engineListener(DebugEngine sourceEngine) {
        return new DebugEngine.Listener() {
            @Override
            public void evaluationChanged() {
                submit(() -> {
                    if (engine == sourceEngine) {
                        breakpointActionResult = sourceEngine.breakpointActionResult();
                        publishRevision();
                    }
                });
            }

            @Override
            public void stopped(DebugEngine.StoppedEvent event) {
                queue.invalidateAdvisoryWork();
                submit(() -> handleStopped(sourceEngine, event));
            }

            @Override
            public void continued(long threadId, boolean allThreadsContinued) {
                queue.invalidateAdvisoryWork();
                submit(() -> {
                    if (engine == sourceEngine) {
                        pausedState = null;
                        breakpointActionResult = sourceEngine.breakpointActionResult();
                        DebugTargetDescriptor currentTarget = target;
                        updateStatus(
                                Phase.RUNNING,
                                currentTarget == null ? "Debugger running" : "Attached to " + currentTarget.displayName(),
                                null
                        );
                    }
                });
            }

            @Override
            public void breakpointChanged(DebugEngine.Breakpoint breakpoint) {
                submit(() -> handleBreakpointChanged(sourceEngine, breakpoint));
            }

            @Override
            public void terminated() {
                queue.invalidateAdvisoryWork();
                submit(() -> {
                    if (engine == sourceEngine) {
                        engine = null;
                        pausedState = null;
                        breakpointActionResult = null;
                        resetBreakpointBindings();
                        try {
                            sourceEngine.close();
                        } catch (Throwable failure) {
                            Throwable cause = unwrap(failure);
                            updateStatus(Phase.FAILED, failureMessage(cause), cause);
                            return;
                        }
                        DebugTargetDescriptor currentTarget = target;
                        if (currentTarget == null) {
                            updateStatus(Phase.UNAVAILABLE, "No Minecraft debug target", null);
                        } else {
                            updateStatus(Phase.DETACHED, currentTarget.displayName() + " is ready to attach", null);
                        }
                    }
                });
            }
        };
    }

    private void handleBreakpointChanged(DebugEngine sourceEngine, DebugEngine.Breakpoint changed) {
        if (this.engine != sourceEngine || changed.id() <= 0) {
            return;
        }
        URI sourceUri;
        List<Breakpoint> snapshot;
        synchronized (this.modelLock) {
            BreakpointKey key = this.engineBreakpointIds.get(changed.id());
            if (key == null) {
                return;
            }
            NavigableMap<Integer, Breakpoint> sourceBreakpoints = this.breakpoints.get(key.sourceUri());
            Breakpoint current = sourceBreakpoints == null ? null : sourceBreakpoints.get(key.line());
            if (current == null || !current.request().equals(key.request())
                    || current.state() == BreakpointState.DISABLED
                    || current.state() == BreakpointState.INVALID) {
                return;
            }
            Breakpoint replacement = DebuggerBreakpointState.adapterResponse(current, changed);
            if (replacement.resolvedLine() == 0 && current.resolvedLine() > 0) {
                replacement = new Breakpoint(
                        replacement.request(),
                        replacement.state(),
                        current.resolvedLine(),
                        replacement.detail()
                );
            }
            sourceBreakpoints.put(current.line(), replacement);
            sourceUri = key.sourceUri();
            snapshot = List.copyOf(sourceBreakpoints.values());
        }
        notifyBreakpointsChanged(sourceUri, snapshot);
    }

    private void handleStopped(DebugEngine sourceEngine, DebugEngine.StoppedEvent event) {
        if (this.engine != sourceEngine) {
            return;
        }
        releaseEvaluationValues();
        this.pauseId = UUID.randomUUID().toString();
        this.pauseGeneration = this.queue.advisoryGeneration();
        this.exposedValues.clear();
        if (breakpointEntries().stream().anyMatch(entry -> entry.breakpoint().request().action() != null)) {
            this.breakpointActionResult = sourceEngine.breakpointActionResult();
        }
        List<DebugEngine.StackFrame> frames = List.of();
        try {
            frames = sourceEngine.stackTrace(event.threadId()).join();
            List<DebugEngine.Variable> variables = frames.isEmpty()
                    ? List.of()
                    : loadVariables(sourceEngine, frames.getFirst());
            if (this.breakpointActionResult != null && !frames.isEmpty()) {
                var action = this.breakpointActionResult;
                var value = action.result();
                List<DebugEngine.Variable> withAction = new ArrayList<>(variables);
                withAction.add(new DebugEngine.Variable("Last breakpoint action", "", "",
                        action.error() != null ? action.error() : value == null ? "" : value.value(),
                        value == null ? "" : value.type(), DebugEngine.VariableKind.EXPRESSION, 0,
                        value == null ? 0 : value.variablesReference(), 0, value == null ? 0 : value.indexedVariables()));
                if (value != null) exposeValue(value.variablesReference(), value.type(), frames.getFirst());
                variables = List.copyOf(withAction);
            }
            PausedState replacement = new PausedState(event, frames, variables);
            this.pausedState = replacement;
            String detail = frames.isEmpty()
                    ? "Paused"
                    : "Paused at " + frames.getFirst().name() + ":" + frames.getFirst().line();
            updateStatus(Phase.PAUSED, detail, null);
            for (Listener listener : this.listeners) {
                listener.paused(replacement);
            }
        } catch (Throwable failure) {
            Throwable cause = unwrap(failure);
            PausedState replacement = new PausedState(event, frames, List.of());
            this.pausedState = replacement;
            updateStatus(Phase.PAUSED, "Unable to inspect paused Minecraft: " + failureMessage(cause), cause);
            for (Listener listener : this.listeners) {
                listener.paused(replacement);
            }
        }
    }

    private List<DebugEngine.Variable> loadVariables(DebugEngine sourceEngine, DebugEngine.StackFrame frame) {
        List<DebugEngine.Scope> scopes = sourceEngine.scopes(frame.id()).join();
        List<DebugEngine.Variable> result = new ArrayList<>();
        for (DebugEngine.Scope scope : scopes) {
            if (!scope.expensive()) {
                result.addAll(sourceEngine.variables(scope.variablesReference(), 0, 0).join());
            }
        }
        return List.copyOf(result);
    }

    private void applyAllBreakpoints(DebugEngine sourceEngine) {
        Map<URI, List<Breakpoint>> snapshot = breakpointSnapshot();
        for (URI sourceUri : snapshot.keySet()) {
            applyBreakpoints(sourceEngine, sourceUri);
        }
    }

    private void applyBreakpoints(DebugEngine sourceEngine, URI sourceUri) {
        if (source(sourceUri) == null) {
            return;
        }
        List<Breakpoint> requested;
        List<Breakpoint> pendingSnapshot;
        synchronized (this.modelLock) {
            NavigableMap<Integer, Breakpoint> sourceBreakpoints = this.breakpoints.get(sourceUri);
            removeSourceBindingsLocked(sourceUri);
            if (sourceBreakpoints == null) {
                requested = List.of();
                pendingSnapshot = List.of();
            } else {
                DebuggerBreakpointState.Application application = DebuggerBreakpointState.application(
                        sourceBreakpoints.values(),
                        this.breakpointsMuted
                );
                requested = application.requested();
                for (Breakpoint pending : application.pending()) {
                    sourceBreakpoints.put(pending.line(), pending);
                }
                pendingSnapshot = List.copyOf(sourceBreakpoints.values());
            }
        }
        notifyBreakpointsChanged(sourceUri, pendingSnapshot);

        List<DebugEngine.Breakpoint> responses = sourceEngine.setBreakpoints(
                sourceUri,
                requested.stream().map(Breakpoint::request).toList()
        ).join();
        if (responses.size() != requested.size()) {
            throw new IllegalStateException(
                    "Debugger returned " + responses.size() + " breakpoint responses for "
                            + requested.size() + " requests"
            );
        }

        List<Breakpoint> responseSnapshot;
        synchronized (this.modelLock) {
            NavigableMap<Integer, Breakpoint> sourceBreakpoints = this.breakpoints.get(sourceUri);
            if (sourceBreakpoints == null) {
                return;
            }
            for (int index = 0; index < requested.size(); index++) {
                Breakpoint requestedBreakpoint = requested.get(index);
                DebugEngine.Breakpoint response = responses.get(index);
                Breakpoint current = sourceBreakpoints.get(requestedBreakpoint.line());
                if (current == null || !current.request().equals(requestedBreakpoint.request())
                        || current.state() == BreakpointState.DISABLED
                        || current.state() == BreakpointState.INVALID) {
                    continue;
                }
                if (response.id() > 0) {
                    this.engineBreakpointIds.put(
                            response.id(),
                            new BreakpointKey(sourceUri, current.line(), current.request())
                    );
                }
                sourceBreakpoints.put(current.line(), DebuggerBreakpointState.adapterResponse(current, response));
            }
            responseSnapshot = List.copyOf(sourceBreakpoints.values());
        }
        notifyBreakpointsChanged(sourceUri, responseSnapshot);
    }

    private List<DebugEngine.Source> sourcesSnapshot() {
        synchronized (this.modelLock) {
            return List.copyOf(this.sources.values());
        }
    }

    private Map<URI, List<Breakpoint>> breakpointSnapshot() {
        synchronized (this.modelLock) {
            Map<URI, List<Breakpoint>> result = new HashMap<>();
            for (Map.Entry<URI, NavigableMap<Integer, Breakpoint>> entry
                    : this.breakpoints.entrySet()) {
                result.put(entry.getKey(), List.copyOf(entry.getValue().values()));
            }
            return result;
        }
    }

    private Breakpoint createdBreakpoint(DebugEngine.Source source, DebugEngine.SourceBreakpoint request) {
        return DebuggerBreakpointState.created(source, request, this.engine != null, this.breakpointsMuted);
    }

    private List<Breakpoint> revalidateBreakpointsLocked(DebugEngine.Source source) {
        NavigableMap<Integer, Breakpoint> sourceBreakpoints = this.breakpoints.get(source.uri());
        if (sourceBreakpoints == null) {
            return null;
        }
        boolean changed = false;
        for (Map.Entry<Integer, Breakpoint> entry : sourceBreakpoints.entrySet()) {
            Breakpoint current = entry.getValue();
            Breakpoint replacement = DebuggerBreakpointState.revalidated(
                    source,
                    current,
                    this.engine != null,
                    this.breakpointsMuted
            );
            if (!replacement.equals(current)) {
                removeBreakpointBindingLocked(source.uri(), current.line());
                entry.setValue(replacement);
                changed = true;
            }
        }
        return changed ? List.copyOf(sourceBreakpoints.values()) : null;
    }

    private void removeBreakpointBindingLocked(URI sourceUri, int line) {
        this.engineBreakpointIds.values().removeIf(
                key -> key.sourceUri().equals(sourceUri) && key.line() == line
        );
    }

    private void removeSourceBindingsLocked(URI sourceUri) {
        this.engineBreakpointIds.values().removeIf(key -> key.sourceUri().equals(sourceUri));
    }

    private void resetBreakpointBindings() {
        Map<URI, List<Breakpoint>> changed = new HashMap<>();
        synchronized (this.modelLock) {
            this.engineBreakpointIds.clear();
            for (Map.Entry<URI, NavigableMap<Integer, Breakpoint>> sourceEntry : this.breakpoints.entrySet()) {
                boolean sourceChanged = false;
                for (Map.Entry<Integer, Breakpoint> breakpointEntry : sourceEntry.getValue().entrySet()) {
                    Breakpoint current = breakpointEntry.getValue();
                    Breakpoint replacement = DebuggerBreakpointState.unbound(current);
                    if (!replacement.equals(current)) {
                        breakpointEntry.setValue(replacement);
                        sourceChanged = true;
                    }
                }
                if (sourceChanged) {
                    changed.put(sourceEntry.getKey(), List.copyOf(sourceEntry.getValue().values()));
                }
            }
        }
        changed.forEach(this::notifyBreakpointsChanged);
    }

    private void notifyBreakpointsChanged(URI sourceUri, List<Breakpoint> snapshot) {
        advanceRevision();
        for (Listener listener : this.listeners) {
            listener.breakpointsChanged(sourceUri, snapshot);
        }
    }

    private PausedState requirePausedState() {
        PausedState pause = this.pausedState;
        if (this.status.phase() != Phase.PAUSED || pause == null) {
            throw new IllegalStateException("Debugger inspection requires state PAUSED");
        }
        return pause;
    }

    private void requirePausedFrame(DebugEngine.StackFrame frame) {
        requireEvaluationIdle();
        PausedState pause = requirePausedState();
        if (!pause.frames().contains(frame)) {
            throw new IllegalArgumentException("Stack frame does not belong to the current pause");
        }
    }

    private static String normalizeExpression(String expression) {
        return expression == null || expression.isBlank() ? null : expression.trim();
    }

    private DebugTargetDescriptor requireTarget() {
        DebugTargetDescriptor current = this.target;
        if (current == null) {
            throw new IllegalStateException("No Minecraft debug target is available");
        }
        return current;
    }

    private DebugEngine requireEngine() {
        DebugEngine current = this.engine;
        if (current == null) {
            throw new IllegalStateException("Debugger is not attached");
        }
        return current;
    }

    private void closeEngine() {
        releaseEvaluationValues();
        DebugEngine current = this.engine;
        this.engine = null;
        this.breakpointActionResult = null;
        resetBreakpointBindings();
        if (current == null) {
            return;
        }
        Throwable failure = null;
        try {
            current.disconnect().join();
        } catch (Throwable exception) {
            failure = unwrap(exception);
        }
        try {
            current.close();
        } catch (Throwable exception) {
            if (failure == null) {
                failure = unwrap(exception);
            } else {
                failure.addSuppressed(unwrap(exception));
            }
        }
        if (failure != null) {
            throw propagate(failure);
        }
    }

    private void releaseEvaluationValues() {
        this.evaluationValues.values().forEach(DebuggerValueLease::close);
        this.evaluationValues.clear();
    }

    private void updateStatus(Phase phase, String detail, Throwable failure) {
        Status replacement = new Status(phase, this.target, detail, failure);
        this.status = replacement;
        publishSnapshot();
        for (Listener listener : this.listeners) {
            listener.statusChanged(replacement);
        }
    }

    private void advanceRevision() {
        synchronized (this.changeMonitor) {
            Snapshot previous = this.snapshot;
            this.snapshot = new Snapshot(previous.revision() + 1, previous.status(),
                    previous.pauseId(), previous.pause());
            this.changeMonitor.notifyAll();
        }
    }

    private void submit(Runnable action) {
        if (this.closed) {
            return;
        }
        this.queue.submit(() -> {
            try {
                action.run();
            } catch (Throwable failure) {
                Throwable cause = unwrap(failure);
                updateStatus(Phase.FAILED, failureMessage(cause), cause);
            }
            return null;
        });
    }

    private CompletableFuture<Void> submitFuture(Runnable action) {
        return submitValue(() -> {
            action.run();
            return null;
        });
    }

    private <T> CompletableFuture<T> submitValue(Supplier<T> action) {
        if (this.closed) {
            return CompletableFuture.failedFuture(new IllegalStateException("Debugger session is closed"));
        }
        return this.queue.submit(() -> {
            try {
                return action.get();
            } catch (Throwable failure) {
                throw propagate(unwrap(failure));
            }
        });
    }

    private void publishSnapshot() {
        synchronized (this.changeMonitor) {
            boolean paused = this.status.phase() == Phase.PAUSED && this.pausedState != null;
            Snapshot previous = this.snapshot;
            if (previous.status().phase() == this.status.phase()
                    && Objects.equals(previous.status().target(), this.status.target())
                    && Objects.equals(previous.pauseId(), paused ? this.pauseId : null)
                    && Objects.equals(previous.pause(), paused ? this.pausedState : null)
                    && Objects.equals(previous.status().failure(), this.status.failure())
                    && (this.status.failure() == null || previous.status().detail().equals(this.status.detail()))) {
                return;
            }
            this.snapshot = new Snapshot(this.snapshot.revision() + 1, this.status,
                    paused ? this.pauseId : null, paused ? this.pausedState : null);
            this.changeMonitor.notifyAll();
        }
    }

    private <T> CompletableFuture<T> submitAdvisory(DebugEngine.StackFrame frame, Supplier<T> action) {
        Objects.requireNonNull(frame, "frame");
        long generation = this.queue.advisoryGeneration();
        return this.queue.submitAdvisory(generation, () -> {
            requirePausedFrame(frame);
            return action.get();
        });
    }

    private static String failureMessage(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static RuntimeException propagate(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new CompletionException(failure);
    }

    @Override
    public void close() {
        if (this.closed) {
            return;
        }
        try {
            submitFuture(() -> {
                closeEngine();
                this.pausedState = null;
                this.target = null;
                updateStatus(Phase.UNAVAILABLE, "Debugger session is closed", null);
            }).join();
        } finally {
            this.closed = true;
            synchronized (this.changeMonitor) {
                this.changeMonitor.notifyAll();
            }
            this.queue.close();
            this.listeners.clear();
        }
    }

    @FunctionalInterface
    private interface ThreadControl {
        CompletableFuture<Void> apply(DebugEngine engine, long threadId);
    }

    private record BreakpointKey(URI sourceUri, int line, DebugEngine.SourceBreakpoint request) {
    }

    private record SourceRegistration(boolean changed, boolean hasBreakpoints, List<Breakpoint> breakpoints) {
        private static final SourceRegistration UNCHANGED = new SourceRegistration(false, false, null);
    }
}
