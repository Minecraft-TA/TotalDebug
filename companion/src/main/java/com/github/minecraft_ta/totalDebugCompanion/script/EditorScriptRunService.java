package com.github.minecraft_ta.totalDebugCompanion.script;

import com.github.minecraft_ta.totaldebug.evaluation.CompilationDiagnostic;
import com.github.minecraft_ta.totalDebugCompanion.notification.NotificationCenter;
import com.github.minecraft_ta.totalDebugCompanion.notification.NotificationCenter.Severity;
import com.github.minecraft_ta.totalDebugCompanion.notification.NotificationCenter.Source;
import com.github.minecraft_ta.totalDebugCompanion.project.ProjectScope;
import com.github.minecraft_ta.totalDebugCompanion.session.CompanionSession;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionResult;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionStatus;
import com.github.minecraft_ta.totaldebug.protocol.execution.ScriptExecutionEnvironment;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ExecutionResultMessage;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Owns editor run observation independently of the lifetime of its result view. */
public final class EditorScriptRunService implements AutoCloseable {
    public enum Phase {
        COMPILING("Compiling"), RUNNING("Running"), STOPPING("Stopping"), COMPLETED("Completed"),
        FAILED("Failed"), CANCELLED("Cancelled"), DISCONNECTED("Disconnected");
        private final String label;
        Phase(String label) { this.label = label; }
        public String label() { return label; }
    }
    public record State(Phase phase, ExecutionResultMessage result, List<CompilationDiagnostic> diagnostics) {
        public boolean terminal() { return result != null && result.result().status().terminal(); }
    }
    public final class Run {
        private final int id;
        private final Source source;
        private final ProjectScope project;
        private boolean quietStop;
        private volatile State state = new State(Phase.COMPILING, null, List.of());
        private final List<Consumer<State>> listeners = new ArrayList<>();
        private boolean stopping;
        private boolean submitted;
        private Phase beforeStop;
        private Run(int id, Source source, ProjectScope project) { this.id = id; this.source = source; this.project = project; }
        public int id() { return id; }
        public Source source() { return source; }
        public State state() { return state; }
        public Runnable subscribe(Consumer<State> listener) {
            State initial;
            synchronized (EditorScriptRunService.this) { if (!state.terminal()) listeners.add(listener); initial = state; }
            listener.accept(initial);
            return () -> { synchronized (EditorScriptRunService.this) { listeners.remove(listener); } };
        }
        public void stop() {
            boolean send;
            synchronized (EditorScriptRunService.this) {
                if (state.terminal() || stopping) return;
                stopping = true;
                quietStop = !project.isActive();
                beforeStop = state.phase();
                send = submitted;
            }
            accept(id, new State(Phase.STOPPING, null, List.of()));
            if (send) requestStop(this);
        }
    }

    private final ScriptExecutionService executions;
    private final CompanionSession session;
    private final NotificationCenter notifications;
    private final Map<Integer, Run> runs = new LinkedHashMap<>();
    private final List<Runnable> listeners = new ArrayList<>();
    private final Consumer<ExecutionResultMessage> resultListener = message -> accept(message.scriptId(), new State(switch (message.result().status()) {
        case CANCELLATION_PENDING -> Phase.STOPPING;
        case RUN_COMPLETED -> Phase.COMPLETED;
        case RUN_EXCEPTION, COMPILATION_FAILED -> Phase.FAILED;
        default -> Phase.RUNNING;
    }, message, List.of()));
    private int nextId;
    private boolean closed;

    public EditorScriptRunService(ScriptExecutionService executions, CompanionSession session, NotificationCenter notifications) {
        this.executions = executions;
        this.session = session;
        this.notifications = notifications;
        session.addExecutionResultListener(resultListener);
    }

    public Run start(ProjectScope project, Source source, String code, boolean server, ScriptExecutionEnvironment environment) {
        Run run;
        synchronized (this) {
            if (closed) throw new IllegalStateException("Editor script service is closed");
            if (nextId == 999_999_999) throw new IllegalStateException("Editor script run identifiers exhausted");
            run = new Run(++nextId, source, project);
            runs.put(run.id, run);
        }
        changed();
        try {
            boolean accepted = executions.run(project, run.id, code, server, environment, failure ->
                    // Local compilation reports RUN_EXCEPTION only for its explicit cancellation path.
                    accept(run.id, new State(failure.result().status() == ExecutionStatus.RUN_EXCEPTION ? Phase.CANCELLED : Phase.FAILED,
                            new ExecutionResultMessage(run.id, failure.result()), failure.diagnostics())));
            if (!accepted) accept(run.id, new State(Phase.FAILED, new ExecutionResultMessage(run.id,
                    ExecutionResult.failed("", null, "Minecraft disconnected or the project changed before submission")), List.of()));
            boolean stop;
            synchronized (this) { run.submitted = true; stop = run.stopping && !run.state.terminal(); }
            if (stop) requestStop(run);
        } catch (RuntimeException failure) {
            accept(run.id, new State(Phase.FAILED, new ExecutionResultMessage(run.id,
                    ExecutionResult.failed("", null, failure.toString())), List.of()));
        }
        return run;
    }

    private void requestStop(Run run) {
        if (executions.stop(run.id)) return;
        boolean report;
        synchronized (this) {
            if (!runs.containsKey(run.id)) return;
            run.stopping = false;
            report = !run.quietStop && !closed;
        }
        accept(run.id, new State(run.beforeStop, null, List.of()));
        if (report) notifications.publish(Severity.ERROR, "Unable to stop script",
                "The stop request could not be sent to Minecraft. Completion has not been confirmed.", run.source);
    }

    private void accept(int id, State state) { accept(id, state, true); }

    private void accept(int id, State state, boolean publish) {
        Run run;
        List<Consumer<State>> targets;
        boolean report;
        synchronized (this) {
            run = runs.get(id);
            if (run == null) return;
            if (run.stopping && !state.terminal()) state = new State(Phase.STOPPING, state.result(), state.diagnostics());
            run.state = state;
            targets = List.copyOf(run.listeners);
            if (state.terminal()) { runs.remove(id); run.listeners.clear(); }
            report = state.terminal() && publish && !run.quietStop && !closed;
        }
        if (report) {
            ExecutionResult result = state.result().result();
            boolean success = result.status() == ExecutionStatus.RUN_COMPLETED;
            boolean cancelled = state.phase() == Phase.CANCELLED;
            String message = cancelled ? "Run cancelled" : success ? "Run completed" : result.status() == ExecutionStatus.COMPILATION_FAILED ? "Compilation failed" : "Run failed";
            notifications.publish(cancelled ? Severity.INFORMATION : success ? Severity.SUCCESS : Severity.ERROR, message,
                    result.error() == null ? "" : result.error().text(), run.source);
        }
        for (Consumer<State> target : targets) target.accept(state);
        changed();
    }

    public synchronized List<Run> activeRuns() { return List.copyOf(runs.values()); }
    public Runnable subscribe(Runnable listener) {
        synchronized (this) { listeners.add(listener); }
        listener.run();
        return () -> { synchronized (this) { listeners.remove(listener); } };
    }
    private void changed() {
        List<Runnable> targets;
        synchronized (this) { targets = List.copyOf(listeners); }
        targets.forEach(Runnable::run);
    }

    public void disconnected(boolean expected) {
        for (Run run : activeRuns()) {
            accept(run.id, new State(Phase.DISCONNECTED, new ExecutionResultMessage(run.id,
                    ExecutionResult.failed("", null, "Minecraft disconnected; script completion could not be confirmed")), List.of()), !expected);
        }
    }

    @Override public void close() {
        synchronized (this) { closed = true; }
        session.removeExecutionResultListener(resultListener);
        disconnected(true);
        synchronized (this) { listeners.clear(); }
    }
}
