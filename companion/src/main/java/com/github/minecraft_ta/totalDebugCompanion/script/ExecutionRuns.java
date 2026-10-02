package com.github.minecraft_ta.totalDebugCompanion.script;

import com.github.minecraft_ta.totalDebugCompanion.project.ProjectScope;
import com.github.minecraft_ta.totalDebugCompanion.project.ProjectScope.InactiveProjectException;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionSession;
import com.github.minecraft_ta.totaldebug.protocol.CompanionProtocol;
import com.github.minecraft_ta.totaldebug.protocol.Side;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionResult;
import com.github.minecraft_ta.totaldebug.protocol.execution.ScriptExecutionEnvironment;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ExecutionResultMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.RelayFailedMessage;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

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

    /** Connection tags change only through per-key map operations, so disconnect checks cannot interleave with them. */
    private record Run(Observer observer, long connection, Side side) { }

    private final CompanionSession session;
    private final ScriptExecutionService scripts;
    private final Map<Integer, Run> runs = new ConcurrentHashMap<>();
    private final BiConsumer<Side, ExecutionResultMessage> listener = (from, message) -> result(from, message.scriptId(), message.result());
    private final Runnable stopTakingRefusals;
    private int lastId;
    private boolean closed;

    public ExecutionRuns(CompanionSession session, ScriptExecutionService scripts) {
        this.session = Objects.requireNonNull(session, "session");
        this.scripts = Objects.requireNonNull(scripts, "scripts");
        session.addExecutionResultListener(this.listener);
        // The refused message and its correlation name the run together.
        this.stopTakingRefusals = session.on(RelayFailedMessage.class, message -> {
            if (message.messageId() == CompanionProtocol.RUN_SCRIPT || message.messageId() == CompanionProtocol.STOP_SCRIPT) {
                relayFailed(message.correlation(), message.reason());
            }
        });
    }

    /** Registers an observer before its source is built. Ids are unique for this Companion process. */
    public synchronized int open(Observer observer) {
        Objects.requireNonNull(observer, "observer");
        if (this.closed) throw new IllegalStateException("Script execution is closed");
        if (this.lastId == Integer.MAX_VALUE) throw new IllegalStateException("Script run identifiers exhausted");
        int id = ++this.lastId;
        this.runs.put(id, new Run(observer, this.session.connection(), Side.CLIENT));
        return id;
    }

    /** Returns false, and forgets the run, when the current connection did not accept it. */
    public boolean submit(int id, ProjectScope project, String source, Side side,
                          ScriptExecutionEnvironment environment) {
        return submit(id, project, source, side, environment, null);
    }

    public boolean submit(int id, ProjectScope project, String source, Side side,
                          ScriptExecutionEnvironment environment, ScriptSubject subject) {
        if (project == null) {
            discard(id);
            return false;
        }
        try {
            // Disconnect cleanup uses this same gate. A run cannot end between registration and compiler admission.
            return project.admit(() -> {
                Run run = this.runs.computeIfPresent(id, (key, opened) -> new Run(opened.observer(), this.session.connection(), side));
                if (run == null) return false;
                boolean sent = false;
                try {
                    sent = this.scripts.run(project, id, source, side, environment, subject, failure -> failed(id, failure));
                    return sent;
                } finally {
                    if (!sent) this.runs.remove(id, run);
                }
            });
        } catch (InactiveProjectException ignored) {
            discard(id);
            return false;
        }
    }

    public boolean stop(int id) {
        Run run = this.runs.get(id);
        return this.scripts.stop(id, run == null ? Side.CLIENT : run.side());
    }

    /**
     * The game no longer plays the server Companion asked about, because it left it or plays another: the runs on that
     * server can no longer report back.
     */
    public void serverSessionEnded() {
        for (int id : List.copyOf(this.runs.keySet())) {
            Run[] ended = new Run[1];
            this.runs.computeIfPresent(id, (key, run) -> {
                if (run.side() != Side.SERVER) return run;
                ended[0] = run;
                return null;
            });
            if (ended[0] != null) {
                ended[0].observer().result(id, ExecutionResult.failed("", null, "Disconnected from the server while the script was running"));
            }
        }
    }

    /** The game client could not carry run {@code id} to the server. */
    private void relayFailed(int id, String reason) {
        Run run = this.runs.remove(id);
        if (run != null) run.observer().result(id, ExecutionResult.failed("", null, reason));
    }

    public ScriptCompilationService.Readiness readiness(Side side) {
        return this.scripts.readiness(side);
    }

    /** Ends runs submitted on the given connection or an earlier one. */
    public void disconnected(long connection, boolean expected) {
        for (int id : List.copyOf(this.runs.keySet())) {
            Run[] ended = new Run[1];
            this.runs.computeIfPresent(id, (key, run) -> {
                if (run.connection() > connection) return run;
                ended[0] = run;
                return null;
            });
            if (ended[0] != null) ended[0].observer().disconnected(id, expected);
        }
    }

    /** Forgets a run that was opened but will not be submitted. */
    public void discard(int id) {
        this.runs.remove(id);
    }

    public void disconnectAll(boolean expected) {
        disconnected(Long.MAX_VALUE, expected);
    }

    /** A result counts only from the side the run went to, so a stale one from the other side cannot end it. */
    private void result(Side from, int id, ExecutionResult result) {
        Run[] matched = new Run[1];
        this.runs.computeIfPresent(id, (key, run) -> {
            if (run.side() != from) return run;
            matched[0] = run;
            return result.status().terminal() ? null : run;
        });
        if (matched[0] != null) matched[0].observer().result(id, result);
    }

    private void failed(int id, ScriptCompilationService.Failure failure) {
        // Local failures are terminal: the script never reached Minecraft.
        Run run = this.runs.remove(id);
        if (run != null) run.observer().failed(id, failure);
    }

    @Override
    public void close() {
        synchronized (this) {
            if (this.closed) return;
            this.closed = true;
        }
        this.session.removeExecutionResultListener(this.listener);
        this.stopTakingRefusals.run();
        disconnectAll(true);
    }
}
