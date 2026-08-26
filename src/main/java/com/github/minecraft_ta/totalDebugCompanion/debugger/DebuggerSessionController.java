package com.github.minecraft_ta.totalDebugCompanion.debugger;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    public interface Listener {
        default void statusChanged(Status status) {
        }

        default void breakpointsChanged(URI sourceUri, List<Breakpoint> breakpoints) {
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
    private final ExecutorService worker;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Map<URI, DebugEngine.Source> sources = new HashMap<>();
    private final Map<URI, NavigableMap<Integer, Breakpoint>> breakpoints = new HashMap<>();
    private final Map<Integer, BreakpointKey> engineBreakpointIds = new HashMap<>();

    private volatile Status status = new Status(Phase.UNAVAILABLE, null, "No Minecraft debug target", null);
    private volatile DebugTargetDescriptor target;
    private volatile DebugEngine engine;
    private volatile PausedState pausedState;
    private volatile boolean closed;

    public DebuggerSessionController() {
        this(binaryName -> null);
    }

    public DebuggerSessionController(SourceLoader sourceLoader) {
        this(
                () -> new MicrosoftJavaDebugEngine(Objects.requireNonNull(sourceLoader, "sourceLoader")),
                new LocalJvmDebugTargetResolver()::resolve
        );
    }

    DebuggerSessionController(Supplier<DebugEngine> engineFactory, TargetResolver targetResolver) {
        this.engineFactory = Objects.requireNonNull(engineFactory, "engineFactory");
        this.targetResolver = Objects.requireNonNull(targetResolver, "targetResolver");
        this.worker = Executors.newSingleThreadExecutor(task -> Thread.ofPlatform()
                .daemon()
                .name("Companion debugger session")
                .unstarted(task));
    }

    public Status status() {
        return this.status;
    }

    public PausedState pausedState() {
        return this.pausedState;
    }

    public void addListener(Listener listener) {
        Listener checked = Objects.requireNonNull(listener, "listener");
        this.listeners.add(checked);
        checked.statusChanged(this.status);
        PausedState currentPause = this.pausedState;
        if (currentPause != null) {
            checked.paused(currentPause);
        }
    }

    public void removeListener(Listener listener) {
        this.listeners.remove(listener);
    }

    public void acceptTarget(DebugTargetDescriptor replacement) {
        Objects.requireNonNull(replacement, "replacement");
        submit(() -> {
            closeEngine();
            this.target = replacement;
            this.pausedState = null;
            updateStatus(Phase.DETACHED, replacement.displayName() + " is ready to attach", null);
        });
    }

    public void clearTarget() {
        submit(() -> {
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
        List<Breakpoint> changedBreakpoints;
        synchronized (this.modelLock) {
            this.sources.put(checked.uri(), checked);
            changedBreakpoints = revalidateBreakpointsLocked(checked);
        }
        if (changedBreakpoints != null) {
            notifyBreakpointsChanged(checked.uri(), changedBreakpoints);
        }
        submit(() -> {
            DebugEngine current = this.engine;
            if (current != null) {
                current.registerSource(checked);
                applyBreakpoints(current, checked.uri());
            }
        });
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
        registerSource(checkedSource);
        boolean enabled;
        List<Breakpoint> snapshot;
        synchronized (this.modelLock) {
            NavigableMap<Integer, Breakpoint> sourceBreakpoints =
                    this.breakpoints.computeIfAbsent(checkedSource.uri(), ignored -> new TreeMap<>());
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
            }
            snapshot = List.copyOf(sourceBreakpoints.values());
        }
        notifyBreakpointsChanged(checkedSource.uri(), snapshot);
        boolean result = enabled;
        return submitFuture(() -> {
            DebugEngine current = this.engine;
            if (current != null) {
                applyBreakpoints(current, checkedSource.uri());
            }
        }).thenApply(ignored -> result);
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
                    ? new DebugEngine.SourceBreakpoint(line, condition, hitCondition, null)
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
        registerSource(checkedSource);
        checkedRequest = checkedRequest.withConditions(
                normalizeExpression(checkedRequest.condition()),
                normalizeExpression(checkedRequest.hitCondition())
        );
        List<Breakpoint> snapshot;
        synchronized (this.modelLock) {
            NavigableMap<Integer, Breakpoint> sourceBreakpoints =
                    this.breakpoints.computeIfAbsent(checkedSource.uri(), ignored -> new TreeMap<>());
            removeBreakpointBindingLocked(checkedSource.uri(), checkedRequest.line());
            sourceBreakpoints.put(
                    checkedRequest.line(),
                    createdBreakpoint(checkedSource, checkedRequest)
            );
            snapshot = List.copyOf(sourceBreakpoints.values());
        }
        notifyBreakpointsChanged(checkedSource.uri(), snapshot);
        return submitFuture(() -> {
            DebugEngine current = this.engine;
            if (current != null) {
                applyBreakpoints(current, checkedSource.uri());
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
        return submitValue(() -> {
            requirePausedFrame(frame);
            return loadVariables(requireEngine(), frame);
        });
    }

    public CompletableFuture<List<DebugEngine.Variable>> variables(int variablesReference) {
        if (variablesReference <= 0) {
            return CompletableFuture.completedFuture(List.of());
        }
        return submitValue(() -> {
            requirePausedState();
            return requireEngine().variables(variablesReference).join();
        });
    }

    public CompletableFuture<DebugEngine.EvaluationResult> evaluate(
            String expression,
            DebugEngine.StackFrame frame
    ) {
        Objects.requireNonNull(frame, "frame");
        return submitValue(() -> {
            requirePausedFrame(frame);
            return requireEngine().evaluate(expression, frame.id()).join();
        });
    }

    private CompletableFuture<Void> control(String detail, ThreadControl control) {
        return submitFuture(() -> {
            DebugEngine current = requireEngine();
            PausedState pause = this.pausedState;
            if (this.status.phase() != Phase.PAUSED || pause == null) {
                throw new IllegalStateException("Debugger control requires state PAUSED");
            }
            this.pausedState = null;
            updateStatus(Phase.RUNNING, detail, null);
            try {
                control.apply(current, pause.event().threadId()).join();
            } catch (Throwable failure) {
                Throwable cause = unwrap(failure);
                this.pausedState = pause;
                updateStatus(Phase.PAUSED, detail + " failed: " + failureMessage(cause), cause);
                throw propagate(cause);
            }
        });
    }

    private DebugEngine.Listener engineListener(DebugEngine sourceEngine) {
        return new DebugEngine.Listener() {
            @Override
            public void stopped(DebugEngine.StoppedEvent event) {
                submit(() -> handleStopped(sourceEngine, event));
            }

            @Override
            public void continued(long threadId, boolean allThreadsContinued) {
                submit(() -> {
                    if (engine == sourceEngine) {
                        pausedState = null;
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
                submit(() -> {
                    if (engine == sourceEngine) {
                        engine = null;
                        pausedState = null;
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
                    || current.state() == BreakpointState.INVALID) {
                return;
            }
            BreakpointState state = changed.verified()
                    ? BreakpointState.BOUND
                    : changed.message().isBlank() ? BreakpointState.PENDING : BreakpointState.INVALID;
            int resolvedLine = changed.line() > 0 ? changed.line() : current.resolvedLine();
            sourceBreakpoints.put(
                    current.line(),
                    new Breakpoint(current.request(), state, resolvedLine, changed.message())
            );
            sourceUri = key.sourceUri();
            snapshot = List.copyOf(sourceBreakpoints.values());
        }
        notifyBreakpointsChanged(sourceUri, snapshot);
    }

    private void handleStopped(DebugEngine sourceEngine, DebugEngine.StoppedEvent event) {
        if (this.engine != sourceEngine) {
            return;
        }
        try {
            List<DebugEngine.StackFrame> frames = sourceEngine.stackTrace(event.threadId()).join();
            List<DebugEngine.Variable> variables = frames.isEmpty()
                    ? List.of()
                    : loadVariables(sourceEngine, frames.getFirst());
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
            PausedState replacement = new PausedState(event, List.of(), List.of());
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
                result.addAll(sourceEngine.variables(scope.variablesReference()).join());
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
                requested = sourceBreakpoints.values().stream()
                        .filter(breakpoint -> breakpoint.state() != BreakpointState.INVALID)
                        .toList();
                for (Breakpoint breakpoint : requested) {
                    Breakpoint current = sourceBreakpoints.get(breakpoint.line());
                    if (current != null && current.request().equals(breakpoint.request())) {
                        sourceBreakpoints.put(
                                current.line(),
                                new Breakpoint(current.request(), BreakpointState.PENDING, 0, "")
                        );
                    }
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
                        || current.state() == BreakpointState.INVALID) {
                    continue;
                }
                if (response.id() > 0) {
                    this.engineBreakpointIds.put(
                            response.id(),
                            new BreakpointKey(sourceUri, current.line(), current.request())
                    );
                }
                BreakpointState state = response.verified()
                        ? BreakpointState.BOUND
                        : response.message().isBlank() ? BreakpointState.PENDING : BreakpointState.INVALID;
                int resolvedLine = response.line() > 0 ? response.line() : 0;
                sourceBreakpoints.put(
                        current.line(),
                        new Breakpoint(current.request(), state, resolvedLine, response.message())
                );
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
        if (isStaticallyInvalid(source, request)) {
            return invalidBreakpoint(request);
        }
        BreakpointState state = this.engine == null ? BreakpointState.UNBOUND : BreakpointState.PENDING;
        return new Breakpoint(request, state, "");
    }

    private List<Breakpoint> revalidateBreakpointsLocked(DebugEngine.Source source) {
        NavigableMap<Integer, Breakpoint> sourceBreakpoints = this.breakpoints.get(source.uri());
        if (sourceBreakpoints == null) {
            return null;
        }
        boolean changed = false;
        for (Map.Entry<Integer, Breakpoint> entry : sourceBreakpoints.entrySet()) {
            Breakpoint current = entry.getValue();
            if (isStaticallyInvalid(source, current.request())) {
                if (current.state() != BreakpointState.INVALID) {
                    removeBreakpointBindingLocked(source.uri(), current.line());
                    entry.setValue(invalidBreakpoint(current.request()));
                    changed = true;
                }
            } else if (current.state() == BreakpointState.INVALID
                    && current.detail().equals(staticInvalidDetail(current.request()))) {
                BreakpointState state = this.engine == null ? BreakpointState.UNBOUND : BreakpointState.PENDING;
                entry.setValue(new Breakpoint(current.request(), state, ""));
                changed = true;
            }
        }
        return changed ? List.copyOf(sourceBreakpoints.values()) : null;
    }

    private static boolean isStaticallyInvalid(
            DebugEngine.Source source,
            DebugEngine.SourceBreakpoint request
    ) {
        if (request.isMethodEntry() && request.debuggerLine() == 0) {
            return true;
        }
        return !source.lineMap().isEmpty()
                && !source.lineMap().containsDisplayedLine(request.debuggerLine());
    }

    private static Breakpoint invalidBreakpoint(DebugEngine.SourceBreakpoint request) {
        return new Breakpoint(
                request,
                BreakpointState.INVALID,
                staticInvalidDetail(request)
        );
    }

    private static String staticInvalidDetail(DebugEngine.SourceBreakpoint request) {
        return request.isMethodEntry()
                ? "Method has no executable bytecode"
                : "No executable bytecode is mapped to line " + request.line();
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
                    if (current.state() == BreakpointState.BOUND || current.state() == BreakpointState.PENDING) {
                        breakpointEntry.setValue(new Breakpoint(current.request(), BreakpointState.UNBOUND, ""));
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
        DebugEngine current = this.engine;
        this.engine = null;
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

    private void updateStatus(Phase phase, String detail, Throwable failure) {
        Status replacement = new Status(phase, this.target, detail, failure);
        this.status = replacement;
        for (Listener listener : this.listeners) {
            listener.statusChanged(replacement);
        }
    }

    private void submit(Runnable action) {
        if (this.closed) {
            return;
        }
        this.worker.execute(() -> {
            try {
                action.run();
            } catch (Throwable failure) {
                Throwable cause = unwrap(failure);
                updateStatus(Phase.FAILED, failureMessage(cause), cause);
            }
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
        CompletableFuture<T> result = new CompletableFuture<>();
        this.worker.execute(() -> {
            try {
                result.complete(action.get());
            } catch (Throwable failure) {
                result.completeExceptionally(unwrap(failure));
            }
        });
        return result;
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
            submitFuture(this::closeEngine).join();
        } finally {
            this.closed = true;
            this.worker.shutdownNow();
            this.listeners.clear();
        }
    }

    @FunctionalInterface
    private interface ThreadControl {
        CompletableFuture<Void> apply(DebugEngine engine, long threadId);
    }

    private record BreakpointKey(URI sourceUri, int line, DebugEngine.SourceBreakpoint request) {
    }
}
