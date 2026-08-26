package com.github.minecraft_ta.totalDebugCompanion.debugger;

import com.github.minecraft_ta.totalDebugCompanion.source.SourceLineMap;

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

    CompletableFuture<EvaluationResult> evaluate(String expression, int frameId);

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

    record Source(URI uri, String binaryName, String contents, SourceLineMap lineMap) {
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
        }

        public Source(URI uri, String binaryName, String contents) {
            this(uri, binaryName, contents, SourceLineMap.empty());
        }
    }

    record SourceBreakpoint(int line, String condition, String hitCondition, String logMessage) {
        public SourceBreakpoint {
            if (line < 1) {
                throw new IllegalArgumentException("Breakpoint line must be positive");
            }
        }

        public SourceBreakpoint(int line) {
            this(line, null, null, null);
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

    record StackFrame(int id, String name, URI sourceUri, int line, int column) {
        public StackFrame {
            name = Objects.requireNonNullElse(name, "");
        }
    }

    record Scope(String name, int variablesReference, boolean expensive) {
        public Scope {
            name = Objects.requireNonNullElse(name, "");
        }
    }

    record Variable(
            String name,
            String value,
            String type,
            int variablesReference,
            int namedVariables,
            int indexedVariables
    ) {
        public Variable {
            name = Objects.requireNonNullElse(name, "");
            value = Objects.requireNonNullElse(value, "");
            type = Objects.requireNonNullElse(type, "");
        }
    }

    record EvaluationResult(String value, String type, int variablesReference, int indexedVariables) {
        public EvaluationResult {
            value = Objects.requireNonNullElse(value, "");
            type = Objects.requireNonNullElse(type, "");
        }
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

        default void terminated() {
        }

        default void output(String text, boolean error) {
        }
    }
}
