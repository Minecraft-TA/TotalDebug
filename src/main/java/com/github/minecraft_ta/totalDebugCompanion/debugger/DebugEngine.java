package com.github.minecraft_ta.totalDebugCompanion.debugger;

import com.github.minecraft_ta.totalDebugCompanion.source.SourceLineMap;
import com.github.minecraft_ta.totalDebugCompanion.source.SourceVariableNames;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public interface DebugEngine extends AutoCloseable {
    void registerSource(Source source);

    void addListener(Listener listener);

    void removeListener(Listener listener);

    State state();

    CompletableFuture<Void> attach(Target target);

    CompletableFuture<List<Breakpoint>> setBreakpoints(URI sourceUri, List<SourceBreakpoint> breakpoints);

    CompletableFuture<Void> start();

    CompletableFuture<List<DebugThread>> threads();

    CompletableFuture<List<StackFrame>> stackTrace(long threadId);

    CompletableFuture<List<Scope>> scopes(int frameId);

    CompletableFuture<List<Variable>> variables(int variablesReference);

    CompletableFuture<Void> setVariable(int variablesReference, String name, String value);

    default CompletableFuture<ValuePreview> preview(int variablesReference) {
        return CompletableFuture.completedFuture(ValuePreview.NONE);
    }

    CompletableFuture<EvaluationResult> evaluate(String expression, int frameId);

    default CompletableFuture<List<DebuggerCompletionProposal>> completions(
            String expression,
            int caret,
            int frameId
    ) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException(
                "Debugger expression completion is unavailable"
        ));
    }

    default CompletableFuture<List<ExpressionToken>> expressionTokens(String expression, int frameId) {
        return CompletableFuture.completedFuture(List.of());
    }

    CompletableFuture<Void> setExceptionBreakpoints(boolean caught, boolean uncaught);

    CompletableFuture<ExceptionInfo> exceptionInfo(long threadId);

    CompletableFuture<Void> pause(long threadId);

    CompletableFuture<Void> stepOver(long threadId);

    CompletableFuture<Void> stepInto(long threadId);

    CompletableFuture<Void> stepOut(long threadId);

    CompletableFuture<Void> resume(long threadId);

    CompletableFuture<Void> disconnect();

    @Override
    void close();

    enum State {
        NEW,
        ATTACHING,
        ATTACHED,
        RUNNING,
        STOPPED,
        TERMINATED,
        CLOSED
    }

    record Target(String host, int port, Duration timeout) {
        public Target {
            if (host == null || host.isBlank()) {
                throw new IllegalArgumentException("Debug target host must not be blank");
            }
            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException("Debug target port must be between 1 and 65535");
            }
            Objects.requireNonNull(timeout, "timeout");
            if (timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("Debug target timeout must be positive");
            }
        }

        public static Target local(int port, Duration timeout) {
            return new Target("127.0.0.1", port, timeout);
        }
    }

    record Source(
            URI uri,
            String binaryName,
            String contents,
            SourceLineMap lineMap,
            SourceVariableNames variableNames
    ) {
        public Source {
            Objects.requireNonNull(uri, "uri");
            if (!uri.isAbsolute()) {
                throw new IllegalArgumentException("Debug source URI must be absolute");
            }
            if (binaryName == null || binaryName.isBlank()) {
                throw new IllegalArgumentException("Debug source binary name must not be blank");
            }
            Objects.requireNonNull(contents, "contents");
            Objects.requireNonNull(lineMap, "lineMap");
            Objects.requireNonNull(variableNames, "variableNames");
        }

        public Source(URI uri, String binaryName, String contents, SourceLineMap lineMap) {
            this(uri, binaryName, contents, lineMap, SourceVariableNames.empty());
        }

        public Source(URI uri, String binaryName, String contents) {
            this(uri, binaryName, contents, SourceLineMap.empty(), SourceVariableNames.empty());
        }
    }

    record MethodTarget(String ownerClassName, String name, String descriptor) {
        public MethodTarget {
            ownerClassName = requireText(ownerClassName, "Method owner class name");
            name = requireText(name, "Method name");
            descriptor = requireText(descriptor, "Method descriptor");
        }

        private static String requireText(String value, String description) {
            Objects.requireNonNull(value, description);
            if (value.isBlank()) {
                throw new IllegalArgumentException(description + " must not be blank");
            }
            return value;
        }
    }

    record SourceBreakpoint(
            int line,
            int debuggerLine,
            MethodTarget method,
            String condition,
            String hitCondition,
            String logMessage
    ) {
        public SourceBreakpoint {
            if (line < 1) {
                throw new IllegalArgumentException("Breakpoint line must be positive");
            }
            if (debuggerLine < 0) {
                throw new IllegalArgumentException("Debugger breakpoint line must not be negative");
            }
            if (method == null && debuggerLine != line) {
                throw new IllegalArgumentException("Line breakpoints must use their displayed line");
            }
        }

        public SourceBreakpoint(int line, String condition, String hitCondition, String logMessage) {
            this(line, line, null, condition, hitCondition, logMessage);
        }

        public SourceBreakpoint(int line) {
            this(line, null, null, null);
        }

        public static SourceBreakpoint methodEntry(
                int declarationLine,
                int debuggerLine,
                MethodTarget method,
                String condition,
                String hitCondition
        ) {
            return new SourceBreakpoint(
                    declarationLine,
                    debuggerLine,
                    Objects.requireNonNull(method, "method"),
                    condition,
                    hitCondition,
                    null
            );
        }

        public boolean isMethodEntry() {
            return this.method != null;
        }

        public SourceBreakpoint withConditions(String condition, String hitCondition) {
            return new SourceBreakpoint(
                    this.line,
                    this.debuggerLine,
                    this.method,
                    condition,
                    hitCondition,
                    this.logMessage
            );
        }
    }

    record Breakpoint(int id, int line, boolean verified, String message) {
        public Breakpoint {
            message = Objects.requireNonNullElse(message, "");
        }
    }

    record StoppedEvent(String reason, long threadId, boolean allThreadsStopped) {
        public StoppedEvent {
            reason = Objects.requireNonNullElse(reason, "");
        }
    }

    record DebugThread(long id, String name) {
        public DebugThread {
            name = Objects.requireNonNullElse(name, "");
        }
    }

    record StackFrame(int id, String name, String binaryName, URI sourceUri, int line, int column) {
        public StackFrame {
            name = Objects.requireNonNullElse(name, "");
            binaryName = Objects.requireNonNullElse(binaryName, "");
        }
    }

    record Scope(String name, int variablesReference, boolean expensive) {
        public Scope {
            name = Objects.requireNonNullElse(name, "");
        }
    }

    enum VariableKind {
        UNKNOWN,
        THIS,
        PARAMETER,
        LOCAL,
        FIELD,
        ARRAY_ELEMENT,
        RETURN_VALUE,
        EXPRESSION
    }

    record Variable(
            String name,
            String adapterName,
            String evaluateName,
            String value,
            String type,
            VariableKind kind,
            int containerReference,
            int variablesReference,
            int namedVariables,
            int indexedVariables
    ) {
        public Variable {
            name = Objects.requireNonNullElse(name, "");
            adapterName = Objects.requireNonNullElse(adapterName, "");
            evaluateName = Objects.requireNonNullElse(evaluateName, "");
            value = Objects.requireNonNullElse(value, "");
            type = Objects.requireNonNullElse(type, "");
            Objects.requireNonNull(kind, "kind");
        }
    }

    record ValuePreview(String summary, String detail) {
        public static final ValuePreview NONE = new ValuePreview("", "");

        public ValuePreview {
            summary = Objects.requireNonNullElse(summary, "");
            detail = Objects.requireNonNullElse(detail, summary);
        }

        public boolean available() {
            return !this.summary.isBlank();
        }
    }

    record EvaluationResult(String value, String type, int variablesReference, int indexedVariables) {
        public EvaluationResult {
            value = Objects.requireNonNullElse(value, "");
            type = Objects.requireNonNullElse(type, "");
        }
    }

    record ExpressionToken(int start, int length, ExpressionTokenKind kind) {
        public ExpressionToken {
            if (start < 0 || length < 1) {
                throw new IllegalArgumentException("Invalid debugger expression token range");
            }
            Objects.requireNonNull(kind, "kind");
        }
    }

    enum ExpressionTokenKind {
        TYPE,
        FIELD,
        METHOD
    }

    record ExceptionInfo(String typeName, String description, String breakMode) {
        public ExceptionInfo {
            typeName = Objects.requireNonNullElse(typeName, "");
            description = Objects.requireNonNullElse(description, "");
            breakMode = Objects.requireNonNullElse(breakMode, "");
        }
    }

    interface Listener {
        default void initialized() {
        }

        default void stopped(StoppedEvent event) {
        }

        default void continued(long threadId, boolean allThreadsContinued) {
        }

        default void breakpointChanged(Breakpoint breakpoint) {
        }

        default void terminated() {
        }

        default void output(String text, boolean error) {
        }
    }
}
