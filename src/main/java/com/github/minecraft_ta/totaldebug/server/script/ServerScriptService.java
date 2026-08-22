package com.github.minecraft_ta.totaldebug.server.script;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.config.TotalDebugConfig;
import com.github.minecraft_ta.totaldebug.network.ForwardedCompanionPayload;
import com.github.minecraft_ta.totaldebug.network.ForwardedScriptStatus;
import com.github.minecraft_ta.totaldebug.network.RunServerScriptPayload;
import com.github.minecraft_ta.totaldebug.script.ScriptCompilerClasspath;
import com.github.minecraft_ta.totaldebug.script.ScriptRunner;
import com.github.minecraft_ta.totaldebug.script.ScriptStatusType;
import com.github.minecraft_ta.totaldebug.tick.TickDomain;
import com.github.minecraft_ta.totaldebug.tick.TickTaskScheduler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Owns isolated server-side script runners for the players that requested them. */
public final class ServerScriptService {
    private final TickTaskScheduler tickTasks;
    private final Map<UUID, RunnerSession> runners = new ConcurrentHashMap<>();
    private ScriptCompilerClasspath compilerClasspath;

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
            sendStatus(server, player, payload.scriptId(), ScriptStatusType.COMPILATION_FAILED,
                    decision.rejectionReason());
            return;
        }

        ScriptRunner runner;
        try {
            runner = runnerFor(server, player);
        } catch (IOException | RuntimeException exception) {
            TotalDebug.LOGGER.error("Unable to prepare the server live-script compiler", exception);
            sendStatus(
                    server,
                    player,
                    payload.scriptId(),
                    ScriptStatusType.COMPILATION_FAILED,
                    "Unable to prepare the server live-script compiler: " + exception.getMessage()
            );
            return;
        }
        runner.runScript(payload.scriptId(), payload.sourceCode(), payload.environment());
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

    private synchronized ScriptRunner runnerFor(MinecraftServer server, ServerPlayer player) throws IOException {
        UUID playerId = player.getUUID();
        RunnerSession existing = this.runners.get(playerId);
        if (existing != null && existing.player() == player) {
            return existing.runner();
        }
        if (existing != null && this.runners.remove(playerId, existing)) {
            existing.runner().close();
        }

        ScriptCompilerClasspath classpath = compilerClasspath();
        ScriptRunner created = new ScriptRunner(
                classpath.argument(),
                TotalDebug.class.getClassLoader(),
                (phase, task) -> this.tickTasks.submit(TickDomain.SERVER, phase, task),
                (scriptId, type, message) -> sendStatus(server, player, scriptId, type, message)
        );
        this.runners.put(playerId, new RunnerSession(player, created));
        return created;
    }

    private synchronized ScriptCompilerClasspath compilerClasspath() throws IOException {
        if (this.compilerClasspath == null) {
            this.compilerClasspath = ScriptCompilerClasspath.discover();
            TotalDebug.LOGGER.info(
                    "Resolved the server live-script compiler classpath from {} runtime sources using Java {} at {}",
                    this.compilerClasspath.sources().size(),
                    System.getProperty("java.version"),
                    System.getProperty("java.home")
            );
            TotalDebug.LOGGER.debug("Server live-script compiler sources: {}", this.compilerClasspath.sources());
        }
        return this.compilerClasspath;
    }

    private static void sendStatus(
            MinecraftServer server,
            ServerPlayer sessionPlayer,
            int scriptId,
            ScriptStatusType type,
            String message
    ) {
        server.execute(() -> {
            ServerPlayer currentPlayer = server.getPlayerList().getPlayer(sessionPlayer.getUUID());
            if (currentPlayer != sessionPlayer
                    || !sessionPlayer.connection.hasChannel(ForwardedCompanionPayload.TYPE)) {
                return;
            }
            sessionPlayer.connection.send(new ForwardedScriptStatus(scriptId, type, message).toPayload());
        });
    }

    private record RunnerSession(ServerPlayer player, ScriptRunner runner) {
    }
}
