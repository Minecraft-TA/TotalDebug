package com.github.minecraft_ta.totaldebug.server.script;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.config.TotalDebugConfig;
import com.github.minecraft_ta.totaldebug.evaluation.ServerManifest;
import com.github.minecraft_ta.totaldebug.network.ForwardedCompanionPayload;
import com.github.minecraft_ta.totaldebug.network.ForwardedExecutionResult;
import com.github.minecraft_ta.totaldebug.network.RunServerScriptPayload;
import com.github.minecraft_ta.totaldebug.network.ServerManifestPayload;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionResult;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionStatus;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ServerManifestMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ServerSourceRequestMessage;
import com.github.minecraft_ta.totaldebug.script.ScriptRunner;
import com.github.minecraft_ta.totaldebug.storage.RuntimePhase;
import com.github.minecraft_ta.totaldebug.tick.TickDomain;
import com.github.minecraft_ta.totaldebug.tick.TickTaskScheduler;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Owns isolated server-side script runners for the players that requested them. */
public final class ServerScriptService {
    private static final int MAX_PENDING_RESULT_ENCODINGS = 4;
    private final ExecutorService manifestWorker = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(64), runnable -> {
        var thread = new Thread(runnable, "TotalDebug server manifest");
        thread.setDaemon(true);
        return thread;
    }, new ThreadPoolExecutor.AbortPolicy());
    private CompletableFuture<ServerManifest.Catalog> manifest;
    private final Map<UUID, ManifestSession> manifestSessions = new ConcurrentHashMap<>();
    private record ManifestSession(ServerPlayer player, String id) {}

    private final TickTaskScheduler tickTasks;
    private final Map<UUID, RunnerSession> runners = new ConcurrentHashMap<>();
    private final ExecutorService resultEncoder = createResultEncoder();

    public ServerScriptService(TickTaskScheduler tickTasks) {
        this.tickTasks = Objects.requireNonNull(tickTasks, "tickTasks");
    }

    public synchronized void sendManifest(ServerPlayer player) {
        if (!player.connection.hasChannel(ServerManifestPayload.TYPE)) return;
        MinecraftServer server = Objects.requireNonNull(player.getServer());
        var session = new ManifestSession(player, UUID.randomUUID().toString());
        this.manifestSessions.put(player.getUUID(), session);
        player.connection.send(new ServerManifestPayload(ServerManifestMessage.unavailable("Preparing server archive baseline")));
        if (this.manifest == null) {
            this.manifest = CompletableFuture.supplyAsync(() -> {
                try (var phase = RuntimePhase.start("server.baseline")) {
                    var sources = TotalDebug.get().runtimeSources();
                    return sources.withCurrentSources(() -> new ServerManifest.Catalog(sources.paths()));
                } catch (IOException exception) {
                    throw new CompletionException(exception);
                }
            }, this.manifestWorker);
        }
        this.manifest.whenComplete((catalog, failure) -> server.execute(() -> {
            if (this.manifestSessions.get(player.getUUID()) != session) return;
            if (failure != null) {
                this.manifestSessions.remove(player.getUUID(), session);
                TotalDebug.LOGGER.error("Unable to prepare server class manifest", failure);
                player.connection.send(new ServerManifestPayload(ServerManifestMessage.unavailable(
                        "Unable to prepare server class manifest; see the server log")));
                return;
            }
            for (var message : ServerManifestMessage.split(session.id(), catalog.baseline())) {
                player.connection.send(new ServerManifestPayload(message));
            }
        }));
    }

    public synchronized void requestSource(ServerPlayer player, ServerSourceRequestMessage request) {
        ManifestSession session = this.manifestSessions.get(player.getUUID());
        if (session == null || session.player() != player || !session.id().equals(request.sessionId())
                || this.manifest == null) return;
        MinecraftServer server = Objects.requireNonNull(player.getServer());
        this.manifest.thenApplyAsync(catalog -> {
            if (this.manifestSessions.get(player.getUUID()) != session) return null;
            try (var phase = RuntimePhase.start("server.source-details")) {
                var sources = TotalDebug.get().runtimeSources();
                return sources.withCurrentSources(() -> catalog.details(request.source()));
            } catch (IOException exception) { throw new CompletionException(exception); }
        }, this.manifestWorker).whenComplete((bytes, failure) -> server.execute(() -> {
            if (this.manifestSessions.get(player.getUUID()) != session) return;
            if (failure != null) {
                TotalDebug.LOGGER.error("Unable to prepare requested server source {}", request.source(), failure);
                player.connection.send(new ServerManifestPayload(new ServerManifestMessage(
                        session.id(), request.requestId(), request.source(),
                        "Unable to prepare server source details; see the server log", 0, 0, new byte[0])));
            } else if (bytes != null) {
                for (var message : ServerManifestMessage.split(session.id(), request.requestId(), request.source(), bytes)) {
                    player.connection.send(new ServerManifestPayload(message));
                }
            }
        }));
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

        ManifestSession manifestSession = this.manifestSessions.get(player.getUUID());
        if (manifestSession == null || manifestSession.player() != player
                || !manifestSession.id().equals(payload.serverSessionId())) {
            sendCompilationFailure(server, player, payload.scriptId(),
                    "The server session changed. Wait for the current handshake and compile again.");
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
        this.manifestSessions.computeIfPresent(player.getUUID(), (id, manifest) ->
                manifest.player() == player ? null : manifest);
        RunnerSession session = this.runners.get(player.getUUID());
        if (session != null
                && session.player() == player
                && this.runners.remove(player.getUUID(), session)) {
            session.runner().close();
        }
    }

    public synchronized void stopAll() {
        this.manifestSessions.clear();
        this.manifest = null;
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
