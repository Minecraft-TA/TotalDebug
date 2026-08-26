package com.github.minecraft_ta.totalDebugCompanion.debugger;

import com.google.gson.JsonObject;
import com.microsoft.java.debug.core.DebugSettings;
import com.microsoft.java.debug.core.JavaBreakpointLocation;
import com.microsoft.java.debug.core.adapter.DebugAdapter;
import com.microsoft.java.debug.core.adapter.HotCodeReplaceEvent;
import com.microsoft.java.debug.core.adapter.ICompletionsProvider;
import com.microsoft.java.debug.core.adapter.IDebugAdapter;
import com.microsoft.java.debug.core.adapter.IEvaluationProvider;
import com.microsoft.java.debug.core.adapter.IHotCodeReplaceProvider;
import com.microsoft.java.debug.core.adapter.IProviderContext;
import com.microsoft.java.debug.core.adapter.ISourceLookUpProvider;
import com.microsoft.java.debug.core.adapter.IVirtualMachineManagerProvider;
import com.microsoft.java.debug.core.adapter.ProviderContext;
import com.microsoft.java.debug.core.protocol.Events;
import com.microsoft.java.debug.core.protocol.IProtocolServer;
import com.microsoft.java.debug.core.protocol.JsonUtils;
import com.microsoft.java.debug.core.protocol.Messages;
import com.microsoft.java.debug.core.protocol.Requests;
import com.microsoft.java.debug.core.protocol.Responses;
import com.microsoft.java.debug.core.protocol.Types;
import com.sun.jdi.Bootstrap;
import io.reactivex.Observable;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class MicrosoftJavaDebugEngine implements DebugEngine {
    private final AtomicInteger requestSequence = new AtomicInteger();
    private final AtomicReference<State> state = new AtomicReference<>(State.NEW);
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final SourceRegistry sourceRegistry = new SourceRegistry();
    private final IDebugAdapter adapter;

    public MicrosoftJavaDebugEngine() {
        configureInitialCoreSettings();

        IProviderContext providers = new ProviderContext();
        providers.registerProvider(
                IVirtualMachineManagerProvider.class,
                (IVirtualMachineManagerProvider) Bootstrap::virtualMachineManager
        );
        providers.registerProvider(ISourceLookUpProvider.class, this.sourceRegistry);
        providers.registerProvider(IEvaluationProvider.class, new SimpleJdiEvaluationProvider());
        providers.registerProvider(IHotCodeReplaceProvider.class, new NoHotCodeReplaceProvider());
        providers.registerProvider(ICompletionsProvider.class, new NoCompletionsProvider());
        this.adapter = new DebugAdapter(new LocalProtocolServer(this::handleEvent), providers);
    }

    private static void configureInitialCoreSettings() {
        DebugSettings settings = DebugSettings.getCurrent();
        settings.showLogicalStructure = false;
        settings.showToString = false;
    }

    @Override
    public void registerSource(Source source) {
        ensureNotClosed();
        this.sourceRegistry.register(Objects.requireNonNull(source, "source"));
    }

    @Override
    public void addListener(Listener listener) {
        this.listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    @Override
    public void removeListener(Listener listener) {
        this.listeners.remove(listener);
    }

    @Override
    public State state() {
        return this.state.get();
    }

    @Override
    public CompletableFuture<Void> attach(Target target) {
        Objects.requireNonNull(target, "target");
        if (!this.state.compareAndSet(State.NEW, State.ATTACHING)) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Debugger attach requires state NEW, current state is " + this.state.get()
            ));
        }

        Requests.InitializeArguments initialize = new Requests.InitializeArguments();
        initialize.clientID = "total-debug-companion";
        initialize.adapterID = "java";
        initialize.pathFormat = "uri";
        initialize.linesStartAt1 = true;
        initialize.columnsStartAt1 = true;
        initialize.supportsVariableType = true;
        initialize.supportsVariablePaging = true;

        Requests.AttachArguments attach = new Requests.AttachArguments();
        attach.hostName = target.host();
        attach.port = target.port();
        attach.timeout = Math.toIntExact(Math.min(Integer.MAX_VALUE, target.timeout().toMillis()));
        attach.sourcePaths = new String[0];
        attach.stepFilters = new Requests.StepFilters();

        return request(Requests.Command.INITIALIZE, initialize, Object.class)
                .thenCompose(ignored -> request(Requests.Command.ATTACH, attach, Object.class))
                .thenRun(() -> this.state.set(State.ATTACHED))
                .whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        this.state.compareAndSet(State.ATTACHING, State.NEW);
                    }
                });
    }

    @Override
    public CompletableFuture<List<Breakpoint>> setBreakpoints(
            URI sourceUri,
            List<SourceBreakpoint> breakpoints
    ) {
        requireState(State.ATTACHED, State.RUNNING, State.STOPPED);
        Objects.requireNonNull(sourceUri, "sourceUri");
        List<SourceBreakpoint> requested = List.copyOf(breakpoints);
        this.sourceRegistry.require(sourceUri);
        if (requested.stream().anyMatch(breakpoint -> breakpoint.logMessage() != null
                && !breakpoint.logMessage().isBlank())) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException(
                    "Logpoints are not implemented"
            ));
        }

        Types.Source source = new Types.Source();
        source.name = sourceName(sourceUri);
        source.path = sourceUri.toString();

        Types.SourceBreakpoint[] coreBreakpoints = requested.stream().map(breakpoint -> {
            Types.SourceBreakpoint core = new Types.SourceBreakpoint(
                    breakpoint.line(),
                    breakpoint.condition(),
                    breakpoint.hitCondition()
            );
            core.logMessage = breakpoint.logMessage();
            return core;
        }).toArray(Types.SourceBreakpoint[]::new);

        Requests.SetBreakpointArguments arguments = new Requests.SetBreakpointArguments();
        arguments.source = source;
        arguments.breakpoints = coreBreakpoints;

        return request(
                Requests.Command.SETBREAKPOINTS,
                arguments,
                Responses.SetBreakpointsResponseBody.class
        ).thenApply(body -> {
            List<Breakpoint> result = new ArrayList<>(body.breakpoints.length);
            for (Types.Breakpoint breakpoint : body.breakpoints) {
                result.add(new Breakpoint(
                        breakpoint.id,
                        breakpoint.line,
                        breakpoint.verified,
                        breakpoint.message
                ));
            }
            return List.copyOf(result);
        });
    }

    @Override
    public CompletableFuture<Void> start() {
        requireState(State.ATTACHED);
        return request(
                Requests.Command.CONFIGURATIONDONE,
                new Requests.ConfigurationDoneArguments(),
                Object.class
        ).thenRun(() -> this.state.compareAndSet(State.ATTACHED, State.RUNNING));
    }

    @Override
    public CompletableFuture<List<DebugThread>> threads() {
        requireState(State.ATTACHED, State.RUNNING, State.STOPPED);
        return request(Requests.Command.THREADS, new Requests.ThreadsArguments(), Responses.ThreadsResponseBody.class)
                .thenApply(body -> {
                    List<DebugThread> result = new ArrayList<>(body.threads.length);
                    for (Types.Thread thread : body.threads) {
                        result.add(new DebugThread(thread.id, normalizeThreadName(thread.name)));
                    }
                    return List.copyOf(result);
                });
    }

    @Override
    public CompletableFuture<List<StackFrame>> stackTrace(long threadId) {
        requireState(State.STOPPED);
        Requests.StackTraceArguments arguments = new Requests.StackTraceArguments();
        arguments.threadId = threadId;

        return request(Requests.Command.STACKTRACE, arguments, Responses.StackTraceResponseBody.class)
                .thenApply(body -> {
                    List<StackFrame> result = new ArrayList<>(body.stackFrames.length);
                    for (Types.StackFrame frame : body.stackFrames) {
                        result.add(new StackFrame(
                                frame.id,
                                frame.name,
                                sourceUri(frame.source),
                                frame.line,
                                frame.column
                        ));
                    }
                    return List.copyOf(result);
                });
    }

    @Override
    public CompletableFuture<List<Scope>> scopes(int frameId) {
        requireState(State.STOPPED);
        Requests.ScopesArguments arguments = new Requests.ScopesArguments();
        arguments.frameId = frameId;

        return request(Requests.Command.SCOPES, arguments, Responses.ScopesResponseBody.class)
                .thenApply(body -> {
                    List<Scope> result = new ArrayList<>(body.scopes.length);
                    for (Types.Scope scope : body.scopes) {
                        result.add(new Scope(scope.name, scope.variablesReference, scope.expensive));
                    }
                    return List.copyOf(result);
                });
    }

    @Override
    public CompletableFuture<List<Variable>> variables(int variablesReference) {
        requireState(State.STOPPED);
        Requests.VariablesArguments arguments = new Requests.VariablesArguments();
        arguments.variablesReference = variablesReference;

        return request(Requests.Command.VARIABLES, arguments, Responses.VariablesResponseBody.class)
                .thenApply(body -> {
                    List<Variable> result = new ArrayList<>(body.variables.length);
                    for (Types.Variable variable : body.variables) {
                        result.add(new Variable(
                                variable.name,
                                variable.value,
                                variable.type,
                                variable.variablesReference,
                                variable.namedVariables,
                                variable.indexedVariables
                        ));
                    }
                    return List.copyOf(result);
                });
    }

    @Override
    public CompletableFuture<EvaluationResult> evaluate(String expression, int frameId) {
        requireState(State.STOPPED);
        if (expression == null || expression.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Expression must not be blank"));
        }
        Requests.EvaluateArguments arguments = new Requests.EvaluateArguments();
        arguments.expression = expression;
        arguments.frameId = frameId;
        arguments.context = "watch";

        return request(Requests.Command.EVALUATE, arguments, Responses.EvaluateResponseBody.class)
                .thenApply(body -> new EvaluationResult(
                        body.result,
                        body.type,
                        body.variablesReference,
                        body.indexedVariables
                ));
    }

    @Override
    public CompletableFuture<Void> setExceptionBreakpoints(boolean caught, boolean uncaught) {
        requireState(State.ATTACHED, State.RUNNING, State.STOPPED);
        List<String> filters = new ArrayList<>(2);
        if (caught) {
            filters.add("caught");
        }
        if (uncaught) {
            filters.add("uncaught");
        }
        Requests.SetExceptionBreakpointsArguments arguments = new Requests.SetExceptionBreakpointsArguments();
        arguments.filters = filters.toArray(String[]::new);
        return request(Requests.Command.SETEXCEPTIONBREAKPOINTS, arguments, Object.class)
                .thenApply(ignored -> null);
    }

    @Override
    public CompletableFuture<ExceptionInfo> exceptionInfo(long threadId) {
        requireState(State.STOPPED);
        Requests.ExceptionInfoArguments arguments = new Requests.ExceptionInfoArguments();
        arguments.threadId = threadId;
        return request(Requests.Command.EXCEPTIONINFO, arguments, Responses.ExceptionInfoResponse.class)
                .thenApply(body -> new ExceptionInfo(
                        body.exceptionId,
                        body.description,
                        body.breakMode == null ? "" : body.breakMode.name()
                ));
    }

    @Override
    public CompletableFuture<Void> pause(long threadId) {
        requireState(State.RUNNING);
        Requests.PauseArguments arguments = new Requests.PauseArguments();
        arguments.threadId = threadId;
        return request(Requests.Command.PAUSE, arguments, Object.class).thenApply(ignored -> null);
    }

    @Override
    public CompletableFuture<Void> stepOver(long threadId) {
        Requests.NextArguments arguments = new Requests.NextArguments();
        arguments.threadId = threadId;
        return resumeWith(Requests.Command.NEXT, arguments);
    }

    @Override
    public CompletableFuture<Void> stepInto(long threadId) {
        Requests.StepInArguments arguments = new Requests.StepInArguments();
        arguments.threadId = threadId;
        return resumeWith(Requests.Command.STEPIN, arguments);
    }

    @Override
    public CompletableFuture<Void> stepOut(long threadId) {
        Requests.StepOutArguments arguments = new Requests.StepOutArguments();
        arguments.threadId = threadId;
        return resumeWith(Requests.Command.STEPOUT, arguments);
    }

    @Override
    public CompletableFuture<Void> resume(long threadId) {
        Requests.ContinueArguments arguments = new Requests.ContinueArguments();
        arguments.threadId = threadId;
        return resumeWith(Requests.Command.CONTINUE, arguments);
    }

    private CompletableFuture<Void> resumeWith(Requests.Command command, Requests.Arguments arguments) {
        requireState(State.STOPPED);
        return request(command, arguments, Object.class).thenApply(ignored -> null);
    }

    @Override
    public CompletableFuture<Void> disconnect() {
        State current = this.state.get();
        if (current == State.NEW || current == State.TERMINATED || current == State.CLOSED) {
            return CompletableFuture.completedFuture(null);
        }

        Requests.DisconnectArguments arguments = new Requests.DisconnectArguments();
        arguments.terminateDebuggee = false;
        return request(Requests.Command.DISCONNECT, arguments, Object.class)
                .handle((ignored, failure) -> {
                    this.state.updateAndGet(state -> state == State.CLOSED ? State.CLOSED : State.TERMINATED);
                    if (failure != null) {
                        if (failure instanceof CompletionException completionException) {
                            throw completionException;
                        }
                        throw new CompletionException(failure);
                    }
                    return null;
                });
    }

    @Override
    public void close() {
        if (this.state.get() == State.CLOSED) {
            return;
        }
        RuntimeException closeFailure = null;
        try {
            disconnect().join();
        } catch (RuntimeException failure) {
            closeFailure = failure;
        } finally {
            this.state.set(State.CLOSED);
            this.listeners.clear();
        }
        if (closeFailure != null) {
            throw closeFailure;
        }
    }

    private <T> CompletableFuture<T> request(
            Requests.Command command,
            Requests.Arguments arguments,
            Class<T> bodyType
    ) {
        JsonObject json = JsonUtils.toJsonTree(arguments, arguments.getClass()).getAsJsonObject();
        Messages.Request request = new Messages.Request(
                this.requestSequence.incrementAndGet(),
                command.getName(),
                json
        );
        return this.adapter.dispatchRequest(request).thenApply(response -> {
            if (!response.success) {
                throw new IllegalStateException(response.message == null
                        ? "Debugger request failed: " + command.getName()
                        : response.message);
            }
            if (bodyType == Object.class || response.body == null) {
                return null;
            }
            if (!bodyType.isInstance(response.body)) {
                throw new IllegalStateException(
                        "Debugger request " + command.getName() + " returned " + response.body.getClass().getName()
                );
            }
            return bodyType.cast(response.body);
        });
    }

    private void handleEvent(Events.DebugEvent event) {
        if (event instanceof Events.InitializedEvent) {
            this.listeners.forEach(Listener::initialized);
            return;
        }
        if (event instanceof Events.StoppedEvent stopped) {
            this.state.set(State.STOPPED);
            StoppedEvent converted = new StoppedEvent(
                    stopped.reason,
                    stopped.threadId,
                    stopped.allThreadsStopped
            );
            this.listeners.forEach(listener -> listener.stopped(converted));
            return;
        }
        if (event instanceof Events.ContinuedEvent continued) {
            this.state.set(State.RUNNING);
            this.listeners.forEach(listener -> listener.continued(
                    continued.threadId,
                    continued.allThreadsContinued
            ));
            return;
        }
        if (event instanceof Events.OutputEvent output) {
            boolean error = output.category == Events.OutputEvent.Category.stderr;
            this.listeners.forEach(listener -> listener.output(output.output, error));
            return;
        }
        if (event instanceof Events.ExitedEvent || event instanceof Events.TerminatedEvent) {
            State previous = this.state.getAndSet(State.TERMINATED);
            if (previous != State.TERMINATED && previous != State.CLOSED) {
                this.listeners.forEach(Listener::terminated);
            }
        }
    }

    private void requireState(State... allowed) {
        State current = this.state.get();
        for (State state : allowed) {
            if (current == state) {
                return;
            }
        }
        throw new IllegalStateException("Debugger operation is unavailable in state " + current);
    }

    private void ensureNotClosed() {
        if (this.state.get() == State.CLOSED) {
            throw new IllegalStateException("Debugger engine is closed");
        }
    }

    private static String sourceName(URI sourceUri) {
        String path = sourceUri.getPath();
        if (path == null || path.isBlank()) {
            return sourceUri.toString();
        }
        int separator = path.lastIndexOf('/');
        return separator < 0 ? path : path.substring(separator + 1);
    }

    private static String normalizeThreadName(String name) {
        if (name != null && name.startsWith("Thread [") && name.endsWith("]")) {
            return name.substring("Thread [".length(), name.length() - 1);
        }
        return name;
    }

    private static URI sourceUri(Types.Source source) {
        if (source == null || source.path == null || source.path.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(source.path);
            return uri.isAbsolute() ? uri : Path.of(source.path).toUri();
        } catch (IllegalArgumentException ignored) {
            return Path.of(source.path).toUri();
        }
    }

    private static final class LocalProtocolServer implements IProtocolServer {
        private final Consumer<Events.DebugEvent> eventHandler;

        private LocalProtocolServer(Consumer<Events.DebugEvent> eventHandler) {
            this.eventHandler = eventHandler;
        }

        @Override
        public CompletableFuture<Messages.Response> sendRequest(Messages.Request request) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException(
                    "Debugger-initiated requests are not supported"
            ));
        }

        @Override
        public CompletableFuture<Messages.Response> sendRequest(Messages.Request request, long timeout) {
            return sendRequest(request);
        }

        @Override
        public void sendEvent(Events.DebugEvent event) {
            this.eventHandler.accept(event);
        }

        @Override
        public void sendResponse(Messages.Response response) {
        }
    }

    private static final class SourceRegistry implements ISourceLookUpProvider {
        private final Map<URI, Source> sourcesByUri = new ConcurrentHashMap<>();
        private final Map<String, Source> sourcesByBinaryName = new ConcurrentHashMap<>();

        private void register(Source source) {
            URI normalizedUri = source.uri().normalize();
            this.sourcesByUri.put(normalizedUri, source);
            this.sourcesByBinaryName.put(source.binaryName(), source);
        }

        private Source require(URI sourceUri) {
            Source source = this.sourcesByUri.get(sourceUri.normalize());
            if (source == null) {
                throw new IllegalArgumentException("No debug source is registered for " + sourceUri);
            }
            return source;
        }

        private Source sourceForClass(String binaryName) {
            Source source = this.sourcesByBinaryName.get(binaryName);
            if (source != null) {
                return source;
            }
            int nestedSeparator = binaryName.indexOf('$');
            return nestedSeparator < 0 ? null : this.sourcesByBinaryName.get(binaryName.substring(0, nestedSeparator));
        }

        @Override
        public boolean supportsRealtimeBreakpointVerification() {
            return true;
        }

        @Override
        @Deprecated
        public String[] getFullyQualifiedName(String uri, int[] lines, int[] columns) {
            Source source = require(URI.create(uri));
            String[] result = new String[lines.length];
            java.util.Arrays.fill(result, source.binaryName());
            return result;
        }

        @Override
        public JavaBreakpointLocation[] getBreakpointLocations(
                String sourceUri,
                Types.SourceBreakpoint[] sourceBreakpoints
        ) {
            Source source = require(URI.create(sourceUri));
            JavaBreakpointLocation[] locations = new JavaBreakpointLocation[sourceBreakpoints.length];
            for (int i = 0; i < sourceBreakpoints.length; i++) {
                JavaBreakpointLocation location = new JavaBreakpointLocation(
                        sourceBreakpoints[i].line,
                        sourceBreakpoints[i].column
                );
                location.setClassName(source.binaryName());
                locations[i] = location;
            }
            return locations;
        }

        @Override
        @Deprecated
        public String getSourceFileURI(String fullyQualifiedName, String sourcePath) {
            Source source = sourceForClass(fullyQualifiedName);
            return source == null ? null : source.uri().toString();
        }

        @Override
        public String getSourceContents(String uri) {
            Source source = this.sourcesByUri.get(URI.create(uri).normalize());
            return source == null ? null : source.contents();
        }

        @Override
        public List<MethodInvocation> findMethodInvocations(String uri, int line) {
            return List.of();
        }
    }

    private static final class NoHotCodeReplaceProvider implements IHotCodeReplaceProvider {
        @Override
        public void onClassRedefined(Consumer<List<String>> consumer) {
        }

        @Override
        public CompletableFuture<List<String>> redefineClasses() {
            return CompletableFuture.completedFuture(List.of());
        }

        @Override
        public Observable<HotCodeReplaceEvent> getEventHub() {
            return Observable.never();
        }
    }

    private static final class NoCompletionsProvider implements ICompletionsProvider {
        @Override
        public List<Types.CompletionItem> codeComplete(
                com.sun.jdi.StackFrame frame,
                String snippet,
                int line,
                int column
        ) {
            return List.of();
        }
    }
}
