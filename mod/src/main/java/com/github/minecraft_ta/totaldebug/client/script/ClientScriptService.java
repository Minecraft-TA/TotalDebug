package com.github.minecraft_ta.totaldebug.client.script;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.client.companion.CompanionAppClient;
import com.github.minecraft_ta.totaldebug.client.companion.message.RunScriptMessage;
import com.github.minecraft_ta.totaldebug.network.ForwardedCompanionPayload;
import com.github.minecraft_ta.totaldebug.network.ForwardedExecutionResult;
import com.github.minecraft_ta.totaldebug.network.ForwardedExecutionResultAssembler;
import com.github.minecraft_ta.totaldebug.network.RunServerScriptPayload;
import com.github.minecraft_ta.totaldebug.network.StopServerScriptPayload;
import com.github.minecraft_ta.totaldebug.script.ScriptCompilerClasspath;
import com.github.minecraft_ta.totaldebug.script.ScriptExecutionEnvironment;
import com.github.minecraft_ta.totaldebug.script.ScriptRunner;
import com.github.minecraft_ta.totaldebug.script.ExecutionResult;
import com.github.minecraft_ta.totaldebug.script.ExecutionResultSink;
import com.github.minecraft_ta.totaldebug.script.ExecutionStatus;
import com.github.minecraft_ta.totaldebug.tick.TickDomain;
import com.github.minecraft_ta.totaldebug.tick.TickTaskScheduler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Routes authenticated Companion script requests to the client or negotiated game server. */
public final class ClientScriptService implements AutoCloseable {
    private enum ExecutionSide {
        CLIENT,
        SERVER
    }

    private record Run(int companionId, int executionId, ExecutionSide side) { }

    private final ExecutionResultSink resultSink;
    private final TickTaskScheduler tickTasks;
    private final ServerScriptTransport serverTransport;
    private final ForwardedExecutionResultAssembler forwardedResults = new ForwardedExecutionResultAssembler();
    private final Map<Integer, Run> activeRuns = new ConcurrentHashMap<>();
    private final Map<Integer, Run> executions = new ConcurrentHashMap<>();
    private long nextExecutionId;
    private ScriptRunner runner;

    public ClientScriptService(CompanionAppClient companionApp, TickTaskScheduler tickTasks) {
        this(
                Objects.requireNonNull(companionApp, "companionApp")::sendExecutionResult,
                tickTasks,
                new ServerScriptTransport.NeoForge()
        );
    }

    ClientScriptService(
            ExecutionResultSink resultSink,
            TickTaskScheduler tickTasks,
            ServerScriptTransport serverTransport
    ) {
        this.resultSink = Objects.requireNonNull(resultSink, "resultSink");
        this.tickTasks = Objects.requireNonNull(tickTasks, "tickTasks");
        this.serverTransport = Objects.requireNonNull(serverTransport, "serverTransport");
    }

    public void handleRunRequest(RunScriptMessage message) {
        Objects.requireNonNull(message, "message");
        ScriptExecutionEnvironment environment;
        try {
            environment = ScriptExecutionEnvironment.fromWireName(message.executionEnvironment());
        } catch (IllegalArgumentException exception) {
            sendUntrackedResult(message.scriptId(), ExecutionStatus.COMPILATION_FAILED, exception.getMessage());
            return;
        }

        if (message.serverSide()) {
            runOnServer(message, environment);
        } else {
            runOnClient(message, environment);
        }
    }

    private void runOnServer(RunScriptMessage message, ScriptExecutionEnvironment environment) {
        ServerScriptTransport.Availability availability = this.serverTransport.availability();
        if (!availability.available()) {
            sendUntrackedResult(
                    message.scriptId(),
                    ExecutionStatus.RUN_EXCEPTION,
                    availability.unavailableReason()
            );
            return;
        }

        Run run = registerRun(message.scriptId(), ExecutionSide.SERVER);
        if (run == null) {
            return;
        }
        RunServerScriptPayload payload;
        try {
            payload = new RunServerScriptPayload(run.executionId(), message.scriptText(), environment);
        } catch (IllegalArgumentException exception) {
            acceptResult(run.executionId(), ExecutionResult.fromStatus(ExecutionStatus.COMPILATION_FAILED,
                    exception.getMessage()), ExecutionSide.SERVER);
            return;
        }
        try {
            this.serverTransport.run(payload);
        } catch (RuntimeException exception) {
            acceptResult(run.executionId(), ExecutionResult.fromStatus(ExecutionStatus.COMPILATION_FAILED,
                    "Unable to send the server script: " + exception.getMessage()), ExecutionSide.SERVER);
        }
    }

    private void runOnClient(RunScriptMessage message, ScriptExecutionEnvironment environment) {
        ScriptRunner activeRunner;
        try {
            activeRunner = runner();
        } catch (IOException | RuntimeException exception) {
            TotalDebug.LOGGER.error("Unable to prepare the live script compiler", exception);
            sendUntrackedResult(
                    message.scriptId(),
                    ExecutionStatus.COMPILATION_FAILED,
                    "Unable to prepare the live script compiler: " + exception.getMessage()
            );
            return;
        }
        Run run = registerRun(message.scriptId(), ExecutionSide.CLIENT);
        if (run == null) {
            return;
        }
        activeRunner.runScript(run.executionId(), message.scriptText(), environment);
    }

    public synchronized void stopScript(int scriptId) {
        Run run = this.activeRuns.get(scriptId);
        if (run == null) {
            return;
        }
        if (run.side() == ExecutionSide.CLIENT) {
            if (this.runner != null) {
                this.runner.stopScript(run.executionId());
            }
            return;
        }
        ServerScriptTransport.Availability availability = this.serverTransport.availability();
        if (!availability.available()) {
            acceptResult(run.executionId(), ExecutionResult.fromStatus(ExecutionStatus.CANCELLATION_PENDING, availability.unavailableReason()),
                    ExecutionSide.SERVER);
            return;
        }
        try {
            this.serverTransport.stop(new StopServerScriptPayload(run.executionId()));
        } catch (RuntimeException exception) {
            acceptResult(run.executionId(), ExecutionResult.fromStatus(ExecutionStatus.CANCELLATION_PENDING,
                    "Unable to stop the server script: " + exception.getMessage()), ExecutionSide.SERVER);
        }
    }

    public synchronized void onServerDisconnect() {
        this.forwardedResults.clear();
        if (this.runner != null) {
            this.runner.stopAll();
        }
        for (Run run : new ArrayList<>(this.executions.values())) {
            if (run.side() == ExecutionSide.SERVER) {
                acceptResult(run.executionId(), ExecutionResult.failed("", null,
                        "Disconnected from the server while the script was running"), ExecutionSide.SERVER);
            }
        }
    }

    public void handleForwardedPayload(ForwardedCompanionPayload payload) {
        ForwardedExecutionResult.Chunk chunk;
        try {
            chunk = ForwardedExecutionResult.decodeChunk(payload);
        } catch (IllegalArgumentException exception) {
            TotalDebug.LOGGER.warn("Discarding invalid forwarded companion payload {}", payload.messageId(), exception);
            return;
        }
        Run run = this.executions.get(chunk.scriptId());
        if (run == null || run.side() != ExecutionSide.SERVER) {
            TotalDebug.LOGGER.warn(
                    "Discarding an execution-result chunk for inactive or client-side script {}",
                    chunk.scriptId()
            );
            return;
        }
        try {
            this.forwardedResults.accept(chunk).ifPresent(result ->
                    acceptResult(result.scriptId(), result.result(), ExecutionSide.SERVER));
        } catch (IllegalArgumentException exception) {
            TotalDebug.LOGGER.warn("Discarding invalid forwarded execution result", exception);
        }
    }

    private synchronized ScriptRunner runner() throws IOException {
        if (this.runner != null) {
            return this.runner;
        }
        ScriptCompilerClasspath classpath = ScriptCompilerClasspath.discover();
        TotalDebug.LOGGER.info(
                "Resolved the live script compiler classpath from {} runtime sources using Java {} at {}",
                classpath.sources().size(),
                System.getProperty("java.version"),
                System.getProperty("java.home")
        );
        TotalDebug.LOGGER.debug("Live script compiler sources: {}", classpath.sources());
        this.runner = new ScriptRunner(
                classpath,
                TotalDebug.class.getClassLoader(),
                (phase, task) -> this.tickTasks.submit(TickDomain.CLIENT, phase, task),
                (scriptId, result) -> acceptResult(scriptId, result, ExecutionSide.CLIENT)
        );
        return this.runner;
    }

    private synchronized Run registerRun(int scriptId, ExecutionSide side) {
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
        // Companion IDs identify editor/job slots. Transport IDs identify executions and are never reused
        // during this Minecraft client's lifetime, including across Companion and server reconnections.
        Run run = new Run(scriptId, (int) this.nextExecutionId++, side);
        this.executions.put(run.executionId(), run);
        this.activeRuns.put(scriptId, run);
        return run;
    }

    private synchronized void acceptResult(
            int scriptId,
            ExecutionResult result,
            ExecutionSide expectedSide
    ) {
        Run run = this.executions.get(scriptId);
        if (run == null || run.side() != expectedSide) {
            TotalDebug.LOGGER.warn(
                    "Discarding stale {} script status {} for script {}",
                    expectedSide.name().toLowerCase(),
                    result.status(),
                    scriptId
            );
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
        this.forwardedResults.clear();
        var detachedRuns = new ArrayList<>(this.activeRuns.values());
        // Detach observers before cancellation can synchronously deliver a result.
        this.activeRuns.clear();
        for (Run run : detachedRuns) {
            if (run.side() != ExecutionSide.SERVER) {
                continue;
            }
            try {
                if (this.serverTransport.availability().available()) {
                    this.serverTransport.stop(new StopServerScriptPayload(run.executionId()));
                }
            } catch (RuntimeException exception) {
                TotalDebug.LOGGER.debug("Unable to stop server script {} while closing", run.executionId(), exception);
            }
        }
        if (this.runner != null) {
            this.runner.close();
            this.runner = null;
        }
    }
}
