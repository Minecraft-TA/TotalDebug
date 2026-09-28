package com.github.minecraft_ta.totaldebug.server.script;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.config.TotalDebugConfig;
import com.github.minecraft_ta.totaldebug.protocol.Side;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionResult;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionStatus;
import com.github.minecraft_ta.totaldebug.protocol.execution.ScriptExecutionEnvironment;
import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectRef;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ExecutionResultMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.RunScriptMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ServerScriptsMessage;
import com.github.minecraft_ta.totaldebug.script.ScriptRunner;
import com.github.minecraft_ta.totaldebug.server.ServerRelay;
import com.github.minecraft_ta.totaldebug.tick.TickTaskScheduler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Runs the scripts players' Companions send through the relay, one runner for each player's current Companion
 * connection. A message from a newer connection, or that connection leaving, ends the runs of the one before. Server
 * thread, except where noted.
 */
public final class ServerScriptService {
    private static final int MAX_PENDING_RESULT_ENCODINGS = 4;
    private final TickTaskScheduler tickTasks;
    private final ServerRelay relay;
    private final Map<UUID, RunnerSession> runners = new ConcurrentHashMap<>();
    private final ExecutorService resultEncoder = createResultEncoder();

    /** Answers Companion through {@code relay}. */
    public ServerScriptService(TickTaskScheduler tickTasks, ServerRelay relay) {
        this.tickTasks = Objects.requireNonNull(tickTasks, "tickTasks");
        this.relay = Objects.requireNonNull(relay, "relay");
    }

    /** Answers {@code request} of Companion connection {@code companion}: whether this server runs {@code player}'s scripts. */
    public void sendAccess(ServerPlayer player, int companion, int request) {
        MinecraftServer server = Objects.requireNonNull(player.getServer(), "player server");
        endEarlierCompanion(player, companion);
        ServerScriptPolicy.Decision decision = policyDecision(server, player);
        this.relay.send(server, player, companion, decision.allowed() ? ServerScriptsMessage.allowed(request)
                : new ServerScriptsMessage(request, decision.rejectionReason()));
    }

    public void runScript(ServerPlayer player, int companion, RunScriptMessage payload) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(payload, "payload");
        MinecraftServer server = Objects.requireNonNull(player.getServer(), "player server");
        endEarlierCompanion(player, companion);
        ScriptExecutionEnvironment environment;
        try {
            environment = ScriptExecutionEnvironment.fromWireName(payload.executionEnvironment());
        } catch (IllegalArgumentException exception) {
            sendCompilationFailure(server, player, companion, payload.scriptId(), exception.getMessage());
            return;
        }
        ServerScriptPolicy.Decision decision = policyDecision(server, player);
        if (!decision.allowed()) {
            sendCompilationFailure(server, player, companion, payload.scriptId(), decision.rejectionReason());
            return;
        }
        String refusal = ServerScriptLinks.refusal(payload.bytecode(), TotalDebug.class.getClassLoader());
        if (!refusal.isEmpty()) {
            sendCompilationFailure(server, player, companion, payload.scriptId(), refusal);
            return;
        }

        SubjectRef.Occurrence subject = null;
        if (!payload.subject().isEmpty()) {
            try {
                subject = SubjectRef.parseOccurrence(payload.subject());
            } catch (IllegalArgumentException exception) {
                sendCompilationFailure(server, player, companion, payload.scriptId(),
                        "Invalid script target: " + exception.getMessage());
                return;
            }
        }

        ScriptRunner runner;
        try {
            runner = runnerFor(server, player, companion);
        } catch (RuntimeException exception) {
            TotalDebug.LOGGER.error("Unable to prepare the server live-script runner", exception);
            sendCompilationFailure(server, player, companion, payload.scriptId(),
                    "Unable to prepare the server live-script runner: " + exception.getMessage());
            return;
        }
        runner.runScript(payload.scriptId(), payload.bytecode(), environment, subject, payload.subjectExpectedId());
    }

    public void stopScript(ServerPlayer player, int companion, int scriptId) {
        Objects.requireNonNull(player, "player");
        RunnerSession session = this.runners.get(player.getUUID());
        if (session != null && session.player() == player && session.companion() == companion) {
            session.runner().stopScript(scriptId);
        }
    }

    /** Companion connection {@code companion} closed: its runs on this server end. */
    public void companionLeft(ServerPlayer player, int companion) {
        RunnerSession session = this.runners.get(player.getUUID());
        if (session != null && session.player() == player && session.companion() == companion) closeRunner(player);
    }

    /** {@code player} left: their runs end. */
    public void endSession(ServerPlayer player) {
        RunnerSession session = this.runners.get(Objects.requireNonNull(player, "player").getUUID());
        if (session != null && session.player() == player) closeRunner(player);
    }

    public synchronized void stopAll() {
        for (RunnerSession session : new ArrayList<>(this.runners.values())) {
            session.runner().close();
        }
        this.runners.clear();
    }

    private static ServerScriptPolicy.Decision policyDecision(MinecraftServer server, ServerPlayer player) {
        ServerScriptPolicy policy = new ServerScriptPolicy(
                TotalDebugConfig.SERVER.enableScripts.get(),
                TotalDebugConfig.SERVER.enableScriptsOnlyForOp.get()
        );
        return policy.evaluate(player.hasPermissions(server.getOperatorUserPermissionLevel()));
    }

    /** A player has one Companion: a message from a newer connection ends the runs of the one before. */
    private void endEarlierCompanion(ServerPlayer player, int companion) {
        RunnerSession session = this.runners.get(player.getUUID());
        if (session != null && (session.player() != player || session.companion() != companion)) closeRunner(player);
    }

    private void closeRunner(ServerPlayer player) {
        RunnerSession session = this.runners.get(player.getUUID());
        if (session != null && this.runners.remove(player.getUUID(), session)) {
            session.runner().close();
        }
    }

    private synchronized ScriptRunner runnerFor(MinecraftServer server, ServerPlayer player, int companion) {
        UUID playerId = player.getUUID();
        RunnerSession existing = this.runners.get(playerId);
        if (existing != null && existing.player() == player && existing.companion() == companion) {
            return existing.runner();
        }
        if (existing != null && this.runners.remove(playerId, existing)) {
            existing.runner().close();
        }
        // A closed runner still reports its stopped runs; they carry its connection's number, which the client drops
        // once another Companion connected.
        ScriptRunner created = new ScriptRunner(
                TotalDebug.class.getClassLoader(),
                (phase, task) -> this.tickTasks.submit(Side.SERVER, phase, task),
                (scriptId, result) -> sendResult(server, player, companion, scriptId, result),
                new ServerScriptTargets(server)
        );
        this.runners.put(playerId, new RunnerSession(player, companion, created));
        return created;
    }

    private void sendCompilationFailure(MinecraftServer server, ServerPlayer player, int companion, int scriptId,
                                        String message) {
        sendResult(server, player, companion, scriptId, ExecutionResult.fromStatus(ExecutionStatus.COMPILATION_FAILED, message));
    }

    /** Any thread. */
    private void sendResult(MinecraftServer server, ServerPlayer player, int companion, int scriptId, ExecutionResult result) {
        try {
            this.resultEncoder.execute(() -> encodeAndSend(server, player, companion, scriptId, result));
        } catch (RejectedExecutionException exception) {
            TotalDebug.LOGGER.warn("Discarding an execution result for script {} because the encoder is overloaded",
                    scriptId);
            this.relay.send(server, player, companion,
                    new ExecutionResultMessage(scriptId, result.deliveryFailure("The server result encoder is overloaded")));
        }
    }

    private void encodeAndSend(MinecraftServer server, ServerPlayer player, int companion, int scriptId, ExecutionResult result) {
        try {
            this.relay.send(server, player, companion, new ExecutionResultMessage(scriptId, result));
        } catch (RuntimeException exception) {
            TotalDebug.LOGGER.error("Unable to encode execution result for script {}", scriptId, exception);
            this.relay.send(server, player, companion, new ExecutionResultMessage(scriptId,
                    result.deliveryFailure("Unable to encode the server execution result")));
        }
    }

    private static ExecutorService createResultEncoder() {
        return new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(MAX_PENDING_RESULT_ENCODINGS),
                runnable -> {
                    Thread thread = new Thread(runnable, "TotalDebug server result encoder");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    private record RunnerSession(ServerPlayer player, int companion, ScriptRunner runner) {
    }
}
