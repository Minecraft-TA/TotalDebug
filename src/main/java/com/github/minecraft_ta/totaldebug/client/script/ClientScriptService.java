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

    private final ExecutionResultSink resultSink;
    private final TickTaskScheduler tickTasks;
    private final ServerScriptTransport serverTransport;
    private final ForwardedExecutionResultAssembler forwardedResults = new ForwardedExecutionResultAssembler();
    private final Map<Integer, ExecutionSide> activeRuns = new ConcurrentHashMap<>();
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

        RunServerScriptPayload payload;
        try {
            payload = new RunServerScriptPayload(message.scriptId(), message.scriptText(), environment);
        } catch (IllegalArgumentException exception) {
            sendUntrackedResult(message.scriptId(), ExecutionStatus.COMPILATION_FAILED, exception.getMessage());
            return;
        }
        if (!registerRun(message.scriptId(), ExecutionSide.SERVER)) {
            return;
        }
        try {
            this.serverTransport.run(payload);
        } catch (RuntimeException exception) {
            this.activeRuns.remove(message.scriptId(), ExecutionSide.SERVER);
            sendUntrackedResult(
                    message.scriptId(),
                    ExecutionStatus.COMPILATION_FAILED,
                    "Unable to send the server script: " + exception.getMessage()
            );
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
        if (!registerRun(message.scriptId(), ExecutionSide.CLIENT)) {
            return;
        }
        activeRunner.runScript(message.scriptId(), message.scriptText(), environment);
    }

    public synchronized void stopScript(int scriptId) {
        ExecutionSide side = this.activeRuns.get(scriptId);
        if (side == ExecutionSide.CLIENT) {
            if (this.runner != null) {
                this.runner.stopScript(scriptId);
            }
            return;
        }
        if (side != ExecutionSide.SERVER) {
            return;
        }

        ServerScriptTransport.Availability availability = this.serverTransport.availability();
        if (!availability.available()) {
            this.activeRuns.remove(scriptId, ExecutionSide.SERVER);
            sendUntrackedResult(scriptId, ExecutionStatus.RUN_EXCEPTION, availability.unavailableReason());
            return;
        }
        try {
            this.serverTransport.stop(new StopServerScriptPayload(scriptId));
        } catch (RuntimeException exception) {
            this.activeRuns.remove(scriptId, ExecutionSide.SERVER);
            sendUntrackedResult(
                    scriptId,
                    ExecutionStatus.RUN_EXCEPTION,
                    "Unable to stop the server script: " + exception.getMessage()
            );
        }
    }

    public synchronized void onServerDisconnect() {
        this.forwardedResults.clear();
        if (this.runner != null) {
            this.runner.stopAll();
        }
        for (Map.Entry<Integer, ExecutionSide> entry : new ArrayList<>(this.activeRuns.entrySet())) {
            if (entry.getValue() == ExecutionSide.SERVER
                    && this.activeRuns.remove(entry.getKey(), ExecutionSide.SERVER)) {
                sendUntrackedResult(
                        entry.getKey(),
                        ExecutionStatus.RUN_EXCEPTION,
                        "Disconnected from the server while the script was running"
                );
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
        if (this.activeRuns.get(chunk.scriptId()) != ExecutionSide.SERVER) {
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

    private synchronized boolean registerRun(int scriptId, ExecutionSide side) {
        if (this.activeRuns.putIfAbsent(scriptId, side) == null) {
            return true;
        }
        sendUntrackedResult(
                scriptId,
                ExecutionStatus.COMPILATION_FAILED,
                "A script with this id is already running"
        );
        return false;
    }

    private synchronized void acceptResult(
            int scriptId,
            ExecutionResult result,
            ExecutionSide expectedSide
    ) {
        boolean accepted = isTerminal(result.status())
                ? this.activeRuns.remove(scriptId, expectedSide)
                : this.activeRuns.get(scriptId) == expectedSide;
        if (!accepted) {
            TotalDebug.LOGGER.warn(
                    "Discarding stale {} script status {} for script {}",
                    expectedSide.name().toLowerCase(),
                    result.status(),
                    scriptId
            );
            return;
        }
        this.resultSink.send(scriptId, result);
    }

    private void sendUntrackedResult(int scriptId, ExecutionStatus type, String message) {
        this.resultSink.send(scriptId, ExecutionResult.fromStatus(type, message));
    }

    private static boolean isTerminal(ExecutionStatus type) {
        return type != ExecutionStatus.COMPILATION_COMPLETED;
    }

    @Override
    public synchronized void close() {
        this.forwardedResults.clear();
        for (Map.Entry<Integer, ExecutionSide> entry : new ArrayList<>(this.activeRuns.entrySet())) {
            if (entry.getValue() != ExecutionSide.SERVER) {
                continue;
            }
            try {
                if (this.serverTransport.availability().available()) {
                    this.serverTransport.stop(new StopServerScriptPayload(entry.getKey()));
                }
            } catch (RuntimeException exception) {
                TotalDebug.LOGGER.debug("Unable to stop server script {} while closing", entry.getKey(), exception);
            }
        }
        this.activeRuns.clear();
        if (this.runner != null) {
            this.runner.close();
            this.runner = null;
        }
    }
}
