package com.github.minecraft_ta.totalDebugCompanion.script;

import com.github.minecraft_ta.totalDebugCompanion.project.ProjectScope;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionSession;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionResult;
import com.github.minecraft_ta.totaldebug.protocol.execution.ScriptExecutionEnvironment;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ExecutionResultMessage;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Allocates script run ids and routes every execution result to the observer that opened the run.
 * A disconnection ends only runs submitted on that connection or an earlier one.
 */
public final class ExecutionRuns implements AutoCloseable {
    /** Receives updates for each run it opened until that run's terminal result or disconnection. */
    public interface Observer {
        void result(int id, ExecutionResult result);

        /** Compilation, cancellation before sending, or submission failed locally. */
        default void failed(int id, ScriptCompilationService.Failure failure) {
            result(id, failure.result());
        }

        /** Minecraft disconnected, so completion of the target code cannot be confirmed. */
        void disconnected(int id, boolean expected);
    }

    private static final class Run {
        private final Observer observer;
        private volatile long connection;

        private Run(Observer observer, long connection) {
            this.observer = observer;
            this.connection = connection;
        }
    }

    private final CompanionSession session;
    private final ScriptExecutionService scripts;
    private final Map<Integer, Run> runs = new ConcurrentHashMap<>();
    private final Consumer<ExecutionResultMessage> listener = message -> result(message.scriptId(), message.result());
    private int lastId;
    private boolean closed;

    public ExecutionRuns(CompanionSession session, ScriptExecutionService scripts) {
        this.session = Objects.requireNonNull(session, "session");
        this.scripts = Objects.requireNonNull(scripts, "scripts");
        session.addExecutionResultListener(this.listener);
    }

    /** Registers an observer before its source is built. Ids are unique for this Companion process. */
    public synchronized int open(Observer observer) {
        Objects.requireNonNull(observer, "observer");
        if (this.closed) throw new IllegalStateException("Script execution is closed");
        if (this.lastId == Integer.MAX_VALUE) throw new IllegalStateException("Script run identifiers exhausted");
        int id = ++this.lastId;
        this.runs.put(id, new Run(observer, this.session.connection()));
        return id;
    }

    /** Returns false, and forgets the run, when the current connection did not accept it. */
    public boolean submit(int id, ProjectScope project, String source, boolean serverSide,
                          ScriptExecutionEnvironment environment) {
        Run run = this.runs.get(id);
        if (run == null) return false;
        run.connection = this.session.connection();
        boolean sent = false;
        try {
            sent = this.scripts.run(project, id, source, serverSide, environment, failure -> failed(id, failure));
        } finally {
            if (!sent) this.runs.remove(id, run);
        }
        return sent;
    }

    public boolean stop(int id) {
        return this.scripts.stop(id);
    }

    /** Ends runs submitted on the given connection or an earlier one. */
    public void disconnected(long connection, boolean expected) {
        for (var entry : List.copyOf(this.runs.entrySet())) {
            Run run = entry.getValue();
            if (run.connection <= connection && this.runs.remove(entry.getKey(), run)) {
                run.observer.disconnected(entry.getKey(), expected);
            }
        }
    }

    public void disconnectAll(boolean expected) {
        disconnected(Long.MAX_VALUE, expected);
    }

    private void result(int id, ExecutionResult result) {
        Run run = result.status().terminal() ? this.runs.remove(id) : this.runs.get(id);
        if (run != null) run.observer.result(id, result);
    }

    private void failed(int id, ScriptCompilationService.Failure failure) {
        // Local failures are terminal: the script never reached Minecraft.
        Run run = this.runs.remove(id);
        if (run != null) run.observer.failed(id, failure);
    }

    @Override
    public void close() {
        synchronized (this) {
            if (this.closed) return;
            this.closed = true;
        }
        this.session.removeExecutionResultListener(this.listener);
        disconnectAll(true);
    }
}
