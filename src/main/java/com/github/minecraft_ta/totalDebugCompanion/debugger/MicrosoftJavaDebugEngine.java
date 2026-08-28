package com.github.minecraft_ta.totalDebugCompanion.debugger;

import com.github.minecraft_ta.totalDebugCompanion.debugger.expression.DebuggerCompletionWire;
import com.github.minecraft_ta.totalDebugCompanion.debugger.expression.RichJavaExpressionEngine;
import com.github.minecraft_ta.totalDebugCompanion.source.SourceVariableNames;
import com.google.gson.JsonObject;
import com.microsoft.java.debug.core.DebugSettings;
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
import com.sun.jdi.VMDisconnectedException;
import io.reactivex.Observable;

import java.net.URI;
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
    private final Map<Integer, Map<String, String>> variableNamesByFrame = new ConcurrentHashMap<>();
    private final Map<Integer, Map<String, String>> variableNamesByScope = new ConcurrentHashMap<>();
    private final Map<Integer, Map<String, VariableKind>> variableKindsByFrame = new ConcurrentHashMap<>();
    private final Map<Integer, Map<String, VariableKind>> variableKindsByScope = new ConcurrentHashMap<>();
    private final Map<Integer, VariableKind> childKindsByReference = new ConcurrentHashMap<>();
    private final MicrosoftSourceRegistry sourceRegistry;
    private final RichJavaExpressionEngine expressionEngine;
    private final IDebugAdapter adapter;

    public MicrosoftJavaDebugEngine(DebuggerSessionController.SourceLoader sourceLoader) {
        configureInitialCoreSettings();
        this.sourceRegistry = new MicrosoftSourceRegistry(sourceLoader);

        IProviderContext providers = new ProviderContext();
        providers.registerProvider(
                IVirtualMachineManagerProvider.class,
                (IVirtualMachineManagerProvider) Bootstrap::virtualMachineManager
        );
        providers.registerProvider(ISourceLookUpProvider.class, this.sourceRegistry);
        this.expressionEngine = new RichJavaExpressionEngine(
                this.sourceRegistry::displayedVariableName,
                this.sourceRegistry::typeScope
        );
        providers.registerProvider(IEvaluationProvider.class, this.expressionEngine);
        providers.registerProvider(IHotCodeReplaceProvider.class, new NoHotCodeReplaceProvider());
        providers.registerProvider(ICompletionsProvider.class, this.expressionEngine);
        this.adapter = new DebugAdapter(new LocalProtocolServer(this::handleEvent), providers);
    }

    private static void configureInitialCoreSettings() {
        DebugSettings settings = DebugSettings.getCurrent();
        settings.showLogicalStructure = false;
        settings.showToString = false;
        settings.showQualifiedNames = true;
        settings.debugSupportOnDecompiledSource = DebugSettings.Switch.ON;
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
        this.sourceRegistry.requireRegistered(sourceUri);
        this.sourceRegistry.prepareBreakpoints(sourceUri, requested);
        if (requested.stream().anyMatch(breakpoint -> breakpoint.debuggerLine() < 1)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "Debugger breakpoint line must be positive"
            ));
        }
        Types.Source source = new Types.Source();
        source.name = sourceName(sourceUri);
        source.path = sourceUri.toString();

        Types.SourceBreakpoint[] coreBreakpoints = requested.stream().map(breakpoint ->
            new Types.SourceBreakpoint(
                    breakpoint.debuggerLine(),
                    breakpoint.condition(),
                    breakpoint.hitCondition()
            )
        ).toArray(Types.SourceBreakpoint[]::new);

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
                        URI uri = MicrosoftSourceRegistry.sourceUri(frame.source);
                        SourceVariableNames variableNames = this.sourceRegistry.variableNames(uri);
                        RichJavaExpressionEngine.FrameVariables frameVariables =
                                this.expressionEngine.frameVariables(frame.id);
                        Map<String, String> frameNames = variableNames.namesForMethod(
                                frameVariables.methodName(),
                                frameVariables.methodDescriptor()
                        );
                        if (!frameNames.isEmpty()) {
                            this.variableNamesByFrame.put(frame.id, frameNames);
                        }
                        this.variableKindsByFrame.put(frame.id, frameVariables.kinds());
                        result.add(new StackFrame(
                                frame.id,
                                frame.name,
                                this.sourceRegistry.binaryName(frame.source, frame.line),
                                uri,
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
                    Map<String, String> variableNames = this.variableNamesByFrame.getOrDefault(
                            frameId,
                            Map.of()
                    );
                    Map<String, VariableKind> variableKinds = this.variableKindsByFrame.getOrDefault(
                            frameId,
                            Map.of()
                    );
                    for (Types.Scope scope : body.scopes) {
                        if (!scope.expensive) {
                            if (!variableNames.isEmpty()) {
                                this.variableNamesByScope.put(scope.variablesReference, variableNames);
                            }
                            this.variableKindsByScope.put(scope.variablesReference, variableKinds);
                        }
                        result.add(new Scope(scope.name, scope.variablesReference, scope.expensive));
                    }
                    return List.copyOf(result);
                });
    }

    @Override
    public CompletableFuture<List<Variable>> variables(int variablesReference, int start, int count) {
        requireState(State.STOPPED);
        if (variablesReference <= 0) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "Variable reference must be positive"
            ));
        }
        if (start < 0) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "Variable page start must not be negative"
            ));
        }
        boolean unpaged = start == 0 && count == 0;
        if (!unpaged && count <= 0) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "Variable page count must be positive, or both start and count must be zero"
            ));
        }
        Map<String, String> variableNames = this.variableNamesByScope.getOrDefault(
                variablesReference,
                Map.of()
        );
        Map<String, VariableKind> scopeKinds = this.variableKindsByScope.get(variablesReference);
        VariableKind childKind = this.childKindsByReference.get(variablesReference);
        if (scopeKinds == null && childKind == null) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "No debugger variable context exists for reference " + variablesReference
            ));
        }
        if (!unpaged && childKind != VariableKind.ARRAY_ELEMENT) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "Only indexed array variables support paged child requests"
            ));
        }
        Requests.VariablesArguments arguments = new Requests.VariablesArguments();
        arguments.variablesReference = variablesReference;
        arguments.start = start;
        arguments.count = count;

        return request(Requests.Command.VARIABLES, arguments, Responses.VariablesResponseBody.class)
                .thenApply(body -> {
                    List<Variable> result = new ArrayList<>(body.variables.length);
                    for (Types.Variable variable : body.variables) {
                        VariableKind kind = scopeKinds == null
                                ? childKind
                                : scopeKinds.getOrDefault(
                                        variable.name,
                                        variable.name.startsWith("->")
                                                ? VariableKind.RETURN_VALUE
                                                : VariableKind.UNKNOWN
                                );
                        registerChildKind(variable.variablesReference, variable.type);
                        result.add(new Variable(
                                variableNames.getOrDefault(variable.name, variable.name),
                                variable.name,
                                variable.evaluateName,
                                variable.value,
                                variable.type,
                                kind,
                                variablesReference,
                                variable.variablesReference,
                                variable.namedVariables,
                                variable.indexedVariables
                        ));
                    }
                    return List.copyOf(result);
                });
    }

    @Override
    public CompletableFuture<Void> setVariable(int variablesReference, String name, String value) {
        requireState(State.STOPPED);
        if (variablesReference <= 0) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "Variable container reference must be positive"
            ));
        }
        if (name == null || name.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Variable name must not be blank"));
        }
        if (value == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Variable value must not be null"));
        }

        Requests.SetVariableArguments arguments = new Requests.SetVariableArguments();
        arguments.variablesReference = variablesReference;
        arguments.name = name;
        arguments.value = value;

        return request(Requests.Command.SETVARIABLE, arguments, Responses.SetVariablesResponseBody.class)
                .thenAccept(body -> registerChildKind(body.variablesReference, body.type));
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
                .thenApply(body -> {
                    registerChildKind(body.variablesReference, body.type);
                    return new EvaluationResult(
                            body.result,
                            body.type,
                            body.variablesReference,
                            body.indexedVariables
                    );
                });
    }

    @Override
    public CompletableFuture<List<DebuggerCompletionProposal>> completions(
            String expression,
            int caret,
            int frameId
    ) {
        requireState(State.STOPPED);
        Requests.CompletionsArguments arguments = new Requests.CompletionsArguments();
        arguments.frameId = frameId;
        arguments.text = expression;
        arguments.line = 0;
        arguments.column = caret;
        return request(Requests.Command.COMPLETIONS, arguments, Responses.CompletionsResponseBody.class)
                .thenApply(body -> {
                    List<DebuggerCompletionProposal> result = new ArrayList<>();
                    if (body == null || body.targets == null) {
                        return result;
                    }
                    for (Types.CompletionItem item : body.targets) {
                        String text = item.text == null ? item.label : item.text;
                        if (text == null || text.isBlank()) {
                            continue;
                        }
                        int start = Math.max(0, item.start);
                        int end = start + Math.max(0, item.number);
                        DebuggerCompletionProposal.Kind kind;
                        try {
                            kind = DebuggerCompletionProposal.Kind.valueOf(
                                    (item.type == null ? "TEXT" : item.type).toUpperCase()
                            );
                        } catch (IllegalArgumentException ignored) {
                            kind = DebuggerCompletionProposal.Kind.FIELD;
                        }
                        DebuggerCompletionWire.Metadata metadata = DebuggerCompletionWire.decode(item.sortText);
                        result.add(new DebuggerCompletionProposal(
                                item.label == null ? text : item.label,
                                text,
                                kind,
                                metadata.detail(),
                                start,
                                end,
                                metadata.caretOffset(),
                                metadata.rank()
                        ));
                    }
                    return List.copyOf(result);
                });
    }

    @Override
    public CompletableFuture<List<ExpressionToken>> expressionTokens(String expression, int frameId) {
        requireState(State.STOPPED);
        try {
            return CompletableFuture.completedFuture(this.expressionEngine.expressionTokens(expression, frameId));
        } catch (Throwable failure) {
            return CompletableFuture.failedFuture(failure);
        }
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
                        Throwable cause = failure;
                        while (cause instanceof CompletionException && cause.getCause() != null) {
                            cause = cause.getCause();
                        }
                        if (cause instanceof VMDisconnectedException) {
                            return null;
                        }
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
            clearVariableNameContexts();
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
            clearVariableNameContexts();
            this.state.set(State.RUNNING);
            this.listeners.forEach(listener -> listener.continued(
                    continued.threadId,
                    continued.allThreadsContinued
            ));
            return;
        }
        if (event instanceof Events.BreakpointEvent changed) {
            Types.Breakpoint breakpoint = changed.breakpoint;
            Breakpoint converted = new Breakpoint(
                    breakpoint.id,
                    breakpoint.line,
                    breakpoint.verified,
                    breakpoint.message
            );
            this.listeners.forEach(listener -> listener.breakpointChanged(converted));
            return;
        }
        if (event instanceof Events.OutputEvent output) {
            boolean error = output.category == Events.OutputEvent.Category.stderr;
            this.listeners.forEach(listener -> listener.output(output.output, error));
            return;
        }
        if (event instanceof Events.ExitedEvent || event instanceof Events.TerminatedEvent) {
            clearVariableNameContexts();
            State previous = this.state.getAndSet(State.TERMINATED);
            if (previous != State.TERMINATED && previous != State.CLOSED) {
                this.listeners.forEach(Listener::terminated);
            }
        }
    }

    private void clearVariableNameContexts() {
        this.variableNamesByFrame.clear();
        this.variableNamesByScope.clear();
        this.variableKindsByFrame.clear();
        this.variableKindsByScope.clear();
        this.childKindsByReference.clear();
    }

    @Override
    public CompletableFuture<ValuePreview> preview(int variablesReference) {
        requireState(State.STOPPED);
        if (variablesReference <= 0) {
            return CompletableFuture.completedFuture(ValuePreview.NONE);
        }
        try {
            return this.expressionEngine.preview(variablesReference);
        } catch (Throwable failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private void registerChildKind(int variablesReference, String type) {
        if (variablesReference <= 0) {
            return;
        }
        this.childKindsByReference.put(
                variablesReference,
                type != null && type.endsWith("[]")
                        ? VariableKind.ARRAY_ELEMENT
                        : VariableKind.FIELD
        );
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

}
