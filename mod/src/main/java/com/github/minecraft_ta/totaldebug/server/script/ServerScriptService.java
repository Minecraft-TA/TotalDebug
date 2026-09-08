package com.github.minecraft_ta.totaldebug.server.script;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.config.TotalDebugConfig;
import com.github.minecraft_ta.totaldebug.network.ForwardedCompanionPayload;
import com.github.minecraft_ta.totaldebug.network.ForwardedExecutionResult;
import com.github.minecraft_ta.totaldebug.network.RunServerScriptPayload;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionResult;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionStatus;
import com.github.minecraft_ta.totaldebug.script.ScriptRunner;
import com.github.minecraft_ta.totaldebug.tick.TickDomain;
import com.github.minecraft_ta.totaldebug.tick.TickTaskScheduler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Owns isolated server-side script runners for the players that requested them. */
public final class ServerScriptService {
    private static final int MAX_PENDING_RESULT_ENCODINGS = 4;
    private final TickTaskScheduler tickTasks;
    private final Map<UUID, RunnerSession> runners = new ConcurrentHashMap<>();
    private final ExecutorService resultEncoder = createResultEncoder();

    public ServerScriptService(TickTaskScheduler tickTasks) {
        this.tickTasks = Objects.requireNonNull(tickTasks, "tickTasks");
    }

    public void runScript(ServerPlayer player, RunServerScriptPayload payload) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(payload, "payload");
        MinecraftServer server = Objects.requireNonNull(player.getServer(), "player server");
        ServerScriptPolicy policy = new ServerScriptPolicy(
                TotalDebugConfig.SERVER.enableScripts.get(),
                TotalDebugConfig.SERVER.enableScriptsOnlyForOp.get()
        );
        ServerScriptPolicy.Decision decision = policy.evaluate(
                player.hasPermissions(server.getOperatorUserPermissionLevel())
        );
        if (!decision.allowed()) {
            sendCompilationFailure(server, player, payload.scriptId(),
                    decision.rejectionReason());
            return;
        }

        ScriptRunner runner;
        try {
            runner = runnerFor(server, player);
        } catch (RuntimeException exception) {
            TotalDebug.LOGGER.error("Unable to prepare the server live-script runner", exception);
            sendCompilationFailure(
                    server,
                    player,
                    payload.scriptId(),
                    "Unable to prepare the server live-script runner: " + exception.getMessage()
            );
            return;
        }
        runner.runScript(payload.scriptId(), payload.bytecode(), payload.environment());
    }

    public void stopScript(ServerPlayer player, int scriptId) {
        Objects.requireNonNull(player, "player");
        RunnerSession session = this.runners.get(player.getUUID());
        if (session != null && session.player() == player) {
            session.runner().stopScript(scriptId);
        }
    }

    public void removePlayer(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        RunnerSession session = this.runners.get(player.getUUID());
        if (session != null
                && session.player() == player
                && this.runners.remove(player.getUUID(), session)) {
            session.runner().close();
        }
    }

    public void stopAll() {
        for (RunnerSession session : new ArrayList<>(this.runners.values())) {
            session.runner().close();
        }
        this.runners.clear();
    }

    private synchronized ScriptRunner runnerFor(MinecraftServer server, ServerPlayer player) {
        UUID playerId = player.getUUID();
        RunnerSession existing = this.runners.get(playerId);
        if (existing != null && existing.player() == player) {
            return existing.runner();
        }
        if (existing != null && this.runners.remove(playerId, existing)) {
            existing.runner().close();
        }

        ScriptRunner created = new ScriptRunner(
                TotalDebug.class.getClassLoader(),
                (phase, task) -> this.tickTasks.submit(TickDomain.SERVER, phase, task),
                (scriptId, result) -> sendResult(server, player, scriptId, result)
        );
        this.runners.put(playerId, new RunnerSession(player, created));
        return created;
    }

    private void sendCompilationFailure(
            MinecraftServer server,
            ServerPlayer sessionPlayer,
            int scriptId,
            String message
    ) {
        sendResult(server, sessionPlayer, scriptId, ExecutionResult.fromStatus(ExecutionStatus.COMPILATION_FAILED, message));
    }

    private void sendResult(
            MinecraftServer server,
            ServerPlayer sessionPlayer,
            int scriptId,
            ExecutionResult result
    ) {
        try {
            this.resultEncoder.execute(() -> encodeAndSend(server, sessionPlayer, scriptId, result));
        } catch (RejectedExecutionException exception) {
            TotalDebug.LOGGER.warn("Discarding an execution result for script {} because the encoder is overloaded",
                    scriptId);
            sendPayloads(
                    server,
                    sessionPlayer,
                    new ForwardedExecutionResult(
                            scriptId,
                            result.deliveryFailure("The server result encoder is overloaded")
                    ).toPayloads()
            );
        }
    }

    private static void encodeAndSend(
            MinecraftServer server,
            ServerPlayer sessionPlayer,
            int scriptId,
            ExecutionResult result
    ) {
        List<ForwardedCompanionPayload> payloads;
        try {
            payloads = new ForwardedExecutionResult(scriptId, result).toPayloads();
        } catch (RuntimeException exception) {
            TotalDebug.LOGGER.error("Unable to encode execution result for script {}", scriptId, exception);
            payloads = new ForwardedExecutionResult(
                    scriptId,
                    result.deliveryFailure("Unable to encode the server execution result")
            ).toPayloads();
        }
        sendPayloads(server, sessionPlayer, payloads);
    }

    private static void sendPayloads(
            MinecraftServer server,
            ServerPlayer sessionPlayer,
            List<ForwardedCompanionPayload> payloads
    ) {
        server.execute(() -> {
            ServerPlayer currentPlayer = server.getPlayerList().getPlayer(sessionPlayer.getUUID());
            if (currentPlayer != sessionPlayer
                    || !sessionPlayer.connection.hasChannel(ForwardedCompanionPayload.TYPE)) {
                return;
            }
            for (ForwardedCompanionPayload payload : payloads) {
                sessionPlayer.connection.send(payload);
            }
        });
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

    private record RunnerSession(ServerPlayer player, ScriptRunner runner) {
    }
}
