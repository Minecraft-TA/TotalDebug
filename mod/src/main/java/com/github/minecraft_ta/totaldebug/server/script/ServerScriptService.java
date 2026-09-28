package com.github.minecraft_ta.totaldebug.server.script;

import com.github.minecraft_ta.totaldebug.protocol.execution.ScriptExecutionEnvironment;
import com.github.minecraft_ta.totaldebug.protocol.scnet.RunScriptMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ExecutionResultMessage;
import com.github.minecraft_ta.totaldebug.server.ServerRelay;
import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.config.TotalDebugConfig;
import com.github.minecraft_ta.totaldebug.evaluation.ServerManifest;
import com.github.minecraft_ta.totaldebug.protocol.Side;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionResult;
import com.github.minecraft_ta.totaldebug.protocol.execution.ExecutionStatus;
import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectRef;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ServerManifestMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ServerSourceRequestMessage;
import com.github.minecraft_ta.totaldebug.runtime.PreparedRuntimeSources;
import com.github.minecraft_ta.totaldebug.script.ScriptRunner;
import com.github.minecraft_ta.totaldebug.storage.RuntimePhase;
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
import java.util.function.BooleanSupplier;

/** Owns isolated server-side script runners for the players that requested them. */
public final class ServerScriptService {
    private static final int MAX_PENDING_RESULT_ENCODINGS = 4;
    private final ExecutorService manifestWorker = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(64), runnable -> {
        var thread = new Thread(runnable, "TotalDebug server manifest");
        thread.setDaemon(true);
        return thread;
    }, new ThreadPoolExecutor.AbortPolicy());
    private CompletableFuture<Manifest> manifest;

    record Manifest(PreparedRuntimeSources sources, ServerManifest.Catalog catalog) {
        byte[] details(int source) throws IOException {
            return sources.withCurrentSources(() -> catalog.details(source));
        }
    }

    private final Map<UUID, ManifestSession> manifestSessions = new ConcurrentHashMap<>();
    private record ManifestSession(ServerPlayer player, String id) {}

    private final TickTaskScheduler tickTasks;
    private final ServerRelay relay;
    private final Map<UUID, RunnerSession> runners = new ConcurrentHashMap<>();
    private final ExecutorService resultEncoder = createResultEncoder();

    /** Answers Companion through {@code relay}. */
    public ServerScriptService(TickTaskScheduler tickTasks, ServerRelay relay) {
        this.tickTasks = Objects.requireNonNull(tickTasks, "tickTasks");
        this.relay = Objects.requireNonNull(relay, "relay");
    }

    /**
     * Starts a new class manifest session for {@code player}, as Companion asked through the relay. A run belongs to the
     * session that took its request, and everything sent about it is dropped once that session ended: the runs of the
     * session before end here, since a Companion started since counts its run ids from the beginning again. Server
     * thread.
     *
     * <p>The manifest serves only server scripts, so a player who may not run them gets no session: a request is a few
     * bytes and its answer up to 32 MiB, which only a player who could run code on the server anyway may ask for.
     */
    public synchronized void sendManifest(ServerPlayer player) {
        if (!ServerRelay.reaches(player)) return;
        MinecraftServer server = Objects.requireNonNull(player.getServer());
        ServerScriptPolicy.Decision decision = policyDecision(server, player);
        if (!decision.allowed()) {
            endSession(player);
            this.relay.send(server, player, ServerManifestMessage.unavailable(decision.rejectionReason()));
            return;
        }
        closeRunner(player);
        var session = new ManifestSession(player, UUID.randomUUID().toString());
        this.manifestSessions.put(player.getUUID(), session);
        this.relay.send(server, player, ServerManifestMessage.unavailable("Preparing server archive baseline"));
        if (this.manifest == null) {
            this.manifest = CompletableFuture.supplyAsync(() -> {
                try (var phase = RuntimePhase.start("server.baseline")) {
                    var sources = TotalDebug.get().runtimeSources();
                    return sources.withCurrentSources(() -> new Manifest(sources, new ServerManifest.Catalog(sources.paths())));
                } catch (IOException exception) {
                    throw new CompletionException(exception);
                }
            }, this.manifestWorker);
        }
        this.manifest.whenComplete((manifest, failure) -> server.execute(() -> {
            if (this.manifestSessions.get(player.getUUID()) != session) return;
            if (failure != null) {
                this.manifestSessions.remove(player.getUUID(), session);
                TotalDebug.LOGGER.error("Unable to prepare server class manifest", failure);
                this.relay.send(server, player, ServerManifestMessage.unavailable(
                        "Unable to prepare server class manifest; see the server log"));
                return;
            }
            for (var message : ServerManifestMessage.split(session.id(), manifest.catalog().baseline())) {
                this.relay.send(server, player, message);
            }
        }));
    }

    public synchronized void requestSource(ServerPlayer player, ServerSourceRequestMessage request) {
        ManifestSession session = this.manifestSessions.get(player.getUUID());
        if (session == null || session.player() != player || !session.id().equals(request.sessionId())
                || this.manifest == null) return;
        MinecraftServer server = Objects.requireNonNull(player.getServer());
        this.manifest.thenApplyAsync(manifest -> {
            if (this.manifestSessions.get(player.getUUID()) != session) return null;
            try (var phase = RuntimePhase.start("server.source-details")) {
                return manifest.details(request.source());
            } catch (IOException exception) { throw new CompletionException(exception); }
        }, this.manifestWorker).whenComplete((bytes, failure) -> server.execute(() -> {
            if (this.manifestSessions.get(player.getUUID()) != session) return;
            if (failure != null) {
                TotalDebug.LOGGER.error("Unable to prepare requested server source {}", request.source(), failure);
                this.relay.send(server, player, new ServerManifestMessage(
                        session.id(), request.requestId(), request.source(),
                        "Unable to prepare server source details; see the server log", 0, 0, new byte[0]));
            } else if (bytes != null) {
                for (var message : ServerManifestMessage.split(session.id(), request.requestId(), request.source(), bytes)) {
                    this.relay.send(server, player, message);
                }
            }
        }));
    }

    public void runScript(ServerPlayer player, RunScriptMessage payload) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(payload, "payload");
        MinecraftServer server = Objects.requireNonNull(player.getServer(), "player server");
        ManifestSession manifestSession = this.manifestSessions.get(player.getUUID());
        BooleanSupplier current = () -> this.manifestSessions.get(player.getUUID()) == manifestSession;
        ScriptExecutionEnvironment environment;
        try {
            environment = ScriptExecutionEnvironment.fromWireName(payload.executionEnvironment());
        } catch (IllegalArgumentException exception) {
            sendCompilationFailure(server, player, payload.scriptId(), exception.getMessage(), current);
            return;
        }
        ServerScriptPolicy.Decision decision = policyDecision(server, player);
        if (!decision.allowed()) {
            sendCompilationFailure(server, player, payload.scriptId(),
                    decision.rejectionReason(), current);
            return;
        }

        if (manifestSession == null || manifestSession.player() != player
                || !manifestSession.id().equals(payload.serverSessionId())) {
            sendCompilationFailure(server, player, payload.scriptId(),
                    "The server session changed. Wait for the current handshake and compile again.", current);
            return;
        }

        SubjectRef.Occurrence subject = null;
        if (!payload.subject().isEmpty()) {
            try {
                subject = SubjectRef.parseOccurrence(payload.subject());
            } catch (IllegalArgumentException exception) {
                sendCompilationFailure(server, player, payload.scriptId(),
                        "Invalid script target: " + exception.getMessage(), current);
                return;
            }
        }

        ScriptRunner runner;
        try {
            runner = runnerFor(server, player, manifestSession, current);
        } catch (RuntimeException exception) {
            TotalDebug.LOGGER.error("Unable to prepare the server live-script runner", exception);
            sendCompilationFailure(
                    server,
                    player,
                    payload.scriptId(),
                    "Unable to prepare the server live-script runner: " + exception.getMessage(),
                    current
            );
            return;
        }
        runner.runScript(payload.scriptId(), payload.bytecode(), environment, subject, payload.subjectExpectedId());
    }

    private static ServerScriptPolicy.Decision policyDecision(MinecraftServer server, ServerPlayer player) {
        ServerScriptPolicy policy = new ServerScriptPolicy(
                TotalDebugConfig.SERVER.enableScripts.get(),
                TotalDebugConfig.SERVER.enableScriptsOnlyForOp.get()
        );
        return policy.evaluate(player.hasPermissions(server.getOperatorUserPermissionLevel()));
    }

    public void stopScript(ServerPlayer player, int scriptId) {
        Objects.requireNonNull(player, "player");
        RunnerSession session = this.runners.get(player.getUUID());
        if (session != null && session.player() == player) {
            session.runner().stopScript(scriptId);
        }
    }

    /** {@code player} left, or their Companion did: their manifest session ends, with the runs in it. Server thread. */
    public void endSession(ServerPlayer player) {
        Objects.requireNonNull(player, "player");
        this.manifestSessions.computeIfPresent(player.getUUID(), (id, manifest) ->
                manifest.player() == player ? null : manifest);
        closeRunner(player);
    }

    private void closeRunner(ServerPlayer player) {
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

    /** The runner of {@code manifestSession}; its results go out only while that session is current. */
    private synchronized ScriptRunner runnerFor(MinecraftServer server, ServerPlayer player, ManifestSession manifestSession,
                                                BooleanSupplier current) {
        UUID playerId = player.getUUID();
        RunnerSession existing = this.runners.get(playerId);
        if (existing != null && existing.player() == player && existing.manifestSession() == manifestSession) {
            return existing.runner();
        }
        if (existing != null && this.runners.remove(playerId, existing)) {
            existing.runner().close();
        }

        ScriptRunner created = new ScriptRunner(
                TotalDebug.class.getClassLoader(),
                (phase, task) -> this.tickTasks.submit(Side.SERVER, phase, task),
                (scriptId, result) -> sendResult(server, player, scriptId, result, current),
                new ServerScriptTargets(server)
        );
        this.runners.put(playerId, new RunnerSession(player, manifestSession, created));
        return created;
    }

    private void sendCompilationFailure(
            MinecraftServer server,
            ServerPlayer sessionPlayer,
            int scriptId,
            String message,
            BooleanSupplier current
    ) {
        sendResult(server, sessionPlayer, scriptId, ExecutionResult.fromStatus(ExecutionStatus.COMPILATION_FAILED, message),
                current);
    }

    private void sendResult(
            MinecraftServer server,
            ServerPlayer sessionPlayer,
            int scriptId,
            ExecutionResult result,
            BooleanSupplier current
    ) {
        try {
            this.resultEncoder.execute(() -> encodeAndSend(server, sessionPlayer, scriptId, result, current));
        } catch (RejectedExecutionException exception) {
            TotalDebug.LOGGER.warn("Discarding an execution result for script {} because the encoder is overloaded",
                    scriptId);
            this.relay.send(server, sessionPlayer,
                    new ExecutionResultMessage(scriptId, result.deliveryFailure("The server result encoder is overloaded")), current);
        }
    }

    private void encodeAndSend(
            MinecraftServer server,
            ServerPlayer sessionPlayer,
            int scriptId,
            ExecutionResult result,
            BooleanSupplier current
    ) {
        try {
            this.relay.send(server, sessionPlayer, new ExecutionResultMessage(scriptId, result), current);
        } catch (RuntimeException exception) {
            TotalDebug.LOGGER.error("Unable to encode execution result for script {}", scriptId, exception);
            this.relay.send(server, sessionPlayer, new ExecutionResultMessage(scriptId,
                    result.deliveryFailure("Unable to encode the server execution result")), current);
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

    private record RunnerSession(ServerPlayer player, ManifestSession manifestSession, ScriptRunner runner) {
    }
}
