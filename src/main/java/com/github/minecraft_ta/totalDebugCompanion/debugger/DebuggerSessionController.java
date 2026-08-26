package com.github.minecraft_ta.totalDebugCompanion.debugger;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
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

    public interface Listener {
        default void statusChanged(Status status) {
        }

        default void breakpointsChanged(URI sourceUri, List<Integer> lines) {
        }

        default void paused(PausedState state) {
        }
    }

    @FunctionalInterface
    interface TargetResolver {
        DebugEngine.Target resolve(DebugTargetDescriptor target, Duration timeout) throws Exception;
    }

    private final Object modelLock = new Object();
    private final Supplier<DebugEngine> engineFactory;
    private final TargetResolver targetResolver;
    private final ExecutorService worker;
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Map<URI, DebugEngine.Source> sources = new HashMap<>();
    private final Map<URI, TreeSet<Integer>> breakpoints = new HashMap<>();

    private volatile Status status = new Status(Phase.UNAVAILABLE, null, "No Minecraft debug target", null);
    private volatile DebugTargetDescriptor target;
    private volatile DebugEngine engine;
    private volatile PausedState pausedState;
    private volatile boolean closed;

    public DebuggerSessionController() {
        this(MicrosoftJavaDebugEngine::new, new LocalJvmDebugTargetResolver()::resolve);
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
        synchronized (this.modelLock) {
            this.sources.put(checked.uri(), checked);
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

    public List<Integer> breakpoints(URI sourceUri) {
        synchronized (this.modelLock) {
            TreeSet<Integer> lines = this.breakpoints.get(sourceUri);
            return lines == null ? List.of() : List.copyOf(lines);
        }
    }

    public CompletableFuture<Boolean> toggleBreakpoint(DebugEngine.Source source, int line) {
        if (line < 1) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Breakpoint line must be positive"));
        }
        registerSource(source);
        boolean enabled;
        List<Integer> snapshot;
        synchronized (this.modelLock) {
            TreeSet<Integer> lines = this.breakpoints.computeIfAbsent(source.uri(), ignored -> new TreeSet<>());
            if (lines.remove(line)) {
                enabled = false;
            } else {
                lines.add(line);
                enabled = true;
            }
            if (lines.isEmpty()) {
                this.breakpoints.remove(source.uri());
            }
            snapshot = List.copyOf(lines);
        }
        for (Listener listener : this.listeners) {
            listener.breakpointsChanged(source.uri(), snapshot);
        }
        boolean result = enabled;
        return submitFuture(() -> {
            DebugEngine current = this.engine;
            if (current != null) {
                applyBreakpoints(current, source.uri());
            }
        }).thenApply(ignored -> result);
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
        return submitValue(() -> loadVariables(requireEngine(), frame));
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
            public void terminated() {
                submit(() -> {
                    if (engine == sourceEngine) {
                        engine = null;
                        pausedState = null;
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
        Map<URI, List<Integer>> snapshot = breakpointSnapshot();
        for (URI sourceUri : snapshot.keySet()) {
            applyBreakpoints(sourceEngine, sourceUri);
        }
    }

    private void applyBreakpoints(DebugEngine sourceEngine, URI sourceUri) {
        if (source(sourceUri) == null) {
            return;
        }
        List<DebugEngine.SourceBreakpoint> requested = breakpoints(sourceUri).stream()
                .map(DebugEngine.SourceBreakpoint::new)
                .toList();
        sourceEngine.setBreakpoints(sourceUri, requested).join();
    }

    private List<DebugEngine.Source> sourcesSnapshot() {
        synchronized (this.modelLock) {
            return List.copyOf(this.sources.values());
        }
    }

    private Map<URI, List<Integer>> breakpointSnapshot() {
        synchronized (this.modelLock) {
            Map<URI, List<Integer>> result = new HashMap<>();
            for (Map.Entry<URI, TreeSet<Integer>> entry : this.breakpoints.entrySet()) {
                result.put(entry.getKey(), List.copyOf(entry.getValue()));
            }
            return result;
        }
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
}
