package com.github.minecraft_ta.totaldebug.client.script;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.protocol.Side;
import com.github.minecraft_ta.totaldebug.client.companion.CompanionAppClient;
import com.github.minecraft_ta.totaldebug.client.inspection.KeptStacks;
import com.github.minecraft_ta.totaldebug.protocol.scnet.RunScriptMessage;
import com.github.minecraft_ta.totaldebug.protocol.execution.ScriptExecutionEnvironment;
import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectRef;
import com.github.minecraft_ta.totaldebug.script.ScriptRunner;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionResult;
import com.github.minecraft_ta.totaldebug.script.ExecutionResultSink;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionStatus;
import com.github.minecraft_ta.totaldebug.tick.TickTaskScheduler;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Runs Companion's client scripts. Server scripts do not pass through here: Companion addresses them to the server,
 * and the relay carries them there unread (see {@code docs/MOD_SIDES.md}).
 */
public final class ClientScriptService implements AutoCloseable {
    private record Run(int companionId, int executionId) { }

    private final ExecutionResultSink resultSink;
    private final TickTaskScheduler tickTasks;
    private final Supplier<String> gameSession;
    private final KeptStacks stacks;
    private final Map<Integer, Run> activeRuns = new ConcurrentHashMap<>();
    private final Map<Integer, Run> executions = new ConcurrentHashMap<>();
    private long nextExecutionId;
    private ScriptRunner runner;

    public ClientScriptService(
            CompanionAppClient companionApp,
            TickTaskScheduler tickTasks,
            Supplier<String> gameSession,
            KeptStacks stacks
    ) {
        this(
                Objects.requireNonNull(companionApp, "companionApp")::sendExecutionResult,
                tickTasks,
                gameSession,
                stacks
        );
    }

    ClientScriptService(
            ExecutionResultSink resultSink,
            TickTaskScheduler tickTasks,
            Supplier<String> gameSession,
            KeptStacks stacks
    ) {
        this.stacks = Objects.requireNonNull(stacks, "stacks");
        this.resultSink = Objects.requireNonNull(resultSink, "resultSink");
        this.tickTasks = Objects.requireNonNull(tickTasks, "tickTasks");
        this.gameSession = Objects.requireNonNull(gameSession, "gameSession");
    }

    public void handleRunRequest(RunScriptMessage message) {
        Objects.requireNonNull(message, "message");
        if (message.side() == Side.SERVER) {
            sendUntrackedResult(message.scriptId(), ExecutionStatus.RUN_EXCEPTION,
                    "A server script reaches the server through the relay, not the client");
            return;
        }
        ScriptExecutionEnvironment environment;
        try {
            environment = ScriptExecutionEnvironment.fromWireName(message.executionEnvironment());
        } catch (IllegalArgumentException exception) {
            sendUntrackedResult(message.scriptId(), ExecutionStatus.COMPILATION_FAILED, exception.getMessage());
            return;
        }
        SubjectRef.Occurrence subject = null;
        if (!message.subject().isEmpty()) {
            if (!message.subjectSessionId().equals(this.gameSession.get())) {
                sendUntrackedResult(message.scriptId(), ExecutionStatus.RUN_EXCEPTION,
                        "The world containing this target was left; inspect it again");
                return;
            }
            try {
                subject = SubjectRef.parseOccurrence(message.subject());
            } catch (IllegalArgumentException exception) {
                sendUntrackedResult(message.scriptId(), ExecutionStatus.COMPILATION_FAILED,
                        "Invalid script target: " + exception.getMessage());
                return;
            }
        }

        runOnClient(message, environment, subject);
    }

    private void runOnClient(RunScriptMessage message, ScriptExecutionEnvironment environment, SubjectRef.Occurrence subject) {
        ScriptRunner activeRunner;
        try {
            activeRunner = runner();
        } catch (RuntimeException exception) {
            TotalDebug.LOGGER.error("Unable to prepare the live script runner", exception);
            sendUntrackedResult(
                    message.scriptId(),
                    ExecutionStatus.COMPILATION_FAILED,
                    "Unable to prepare the live script runner: " + exception.getMessage()
            );
            return;
        }
        Run run = registerRun(message.scriptId());
        if (run == null) {
            return;
        }
        activeRunner.runScript(run.executionId(), message.bytecode(), environment, subject,
                message.subjectExpectedId());
    }

    public synchronized void stopScript(int scriptId) {
        Run run = this.activeRuns.get(scriptId);
        if (run != null && this.runner != null) this.runner.stopScript(run.executionId());
    }

    /** The player left the world: its client scripts stop. */
    public synchronized void onServerDisconnect() {
        if (this.runner != null) {
            this.runner.stopAll();
        }
    }

    private synchronized ScriptRunner runner() {
        if (this.runner != null) {
            return this.runner;
        }
        this.runner = new ScriptRunner(
                TotalDebug.class.getClassLoader(),
                (phase, task) -> this.tickTasks.submit(Side.CLIENT, phase, task),
                this::acceptResult,
                new ClientScriptTargets(this.stacks)
        );
        return this.runner;
    }

    private synchronized Run registerRun(int scriptId) {
        if (this.activeRuns.containsKey(scriptId)) {
            sendUntrackedResult(scriptId, ExecutionStatus.COMPILATION_FAILED,
                    "A script with this id is already running");
            return null;
        }
        if (this.nextExecutionId > Integer.MAX_VALUE) {
            sendUntrackedResult(scriptId, ExecutionStatus.COMPILATION_FAILED,
                    "Script execution id space exhausted; restart Minecraft");
            return null;
        }
        // Companion IDs identify editor/job slots. Execution IDs are never reused during this Minecraft client's
        // lifetime, including across Companion reconnections.
        Run run = new Run(scriptId, (int) this.nextExecutionId++);
        this.executions.put(run.executionId(), run);
        this.activeRuns.put(scriptId, run);
        return run;
    }

    private synchronized void acceptResult(int scriptId, ExecutionResult result) {
        Run run = this.executions.get(scriptId);
        if (run == null) {
            TotalDebug.LOGGER.warn("Discarding stale script status {} for script {}", result.status(), scriptId);
            return;
        }
        boolean observed = this.activeRuns.get(run.companionId()) == run;
        if (isTerminal(result.status())) {
            this.executions.remove(scriptId, run);
            this.activeRuns.remove(run.companionId(), run);
        }
        if (observed) {
            this.resultSink.send(run.companionId(), result);
        }
    }

    private void sendUntrackedResult(int scriptId, ExecutionStatus type, String message) {
        this.resultSink.send(scriptId, ExecutionResult.fromStatus(type, message));
    }

    private static boolean isTerminal(ExecutionStatus type) {
        return type.terminal();
    }

    @Override
    public synchronized void close() {
        // Detach observers before cancellation can synchronously deliver a result.
        this.activeRuns.clear();
        if (this.runner != null) {
            this.runner.close();
            this.runner = null;
        }
    }
}
