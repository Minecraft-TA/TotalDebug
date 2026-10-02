package com.github.minecraft_ta.totalDebugCompanion.script;

import com.github.minecraft_ta.totalDebugCompanion.session.MessageRoutes;
import com.github.minecraft_ta.totaldebug.protocol.CompanionProtocol;
import com.github.minecraft_ta.totaldebug.protocol.message.PlayingPayload;
import com.github.minecraft_ta.totaldebug.protocol.scnet.PlayingMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.RelayFailedMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ServerScriptsMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ServerScriptsRequestMessage;
import com.github.tth05.scnet.message.AbstractMessage;

import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

/**
 * Whether the server the game plays runs this player's scripts (docs/GAME_MESSAGES.md): asks it once the game plays a
 * world or a server with TotalDebug, and again only when it plays another, and tells the compiler what it answered. The
 * runs on a server the game left end then, since they can no longer report back. Lives as long as the application, beside
 * the compiler and the runs it tells.
 */
public final class ServerScriptAccess implements AutoCloseable {
    /** Sends {@code message} to the server of {@code world} through the game client; false when it could not be sent. */
    @FunctionalInterface
    public interface Sender {
        boolean send(AbstractMessage message, int correlation, String world);
    }

    private final Sender sender;
    private final BooleanSupplier admitted;
    /** Tells the compiler the world whose server was asked and what it answered, or why it cannot be asked. */
    private final BiConsumer<String, String> compiler;
    /** Ends the runs on the server, which can no longer report back. */
    private final Runnable serverRunsEnded;
    private final List<Runnable> routes;
    /** The world whose server was last asked on this connection, as {@code PLAYING} names it, or null. */
    private String target;
    /** The request that waits for an answer, or 0; they count down from -1, script runs up from 1. */
    private int waiting;
    private int lastRequest;

    /**
     * Takes the game's {@code PLAYING}, the server's answers and the requests the game could not carry from {@code routes};
     * asks only while {@code admitted} holds, as not during a project switch. Tells {@code compiler} the access, as
     * {@link ScriptCompilationService#serverAccess} takes it, and {@code serverRunsEnded} when the game left a server.
     */
    public ServerScriptAccess(MessageRoutes routes, Sender sender, BooleanSupplier admitted, BiConsumer<String, String> compiler,
                              Runnable serverRunsEnded) {
        this.sender = Objects.requireNonNull(sender, "sender");
        this.admitted = Objects.requireNonNull(admitted, "admitted");
        this.compiler = Objects.requireNonNull(compiler, "compiler");
        this.serverRunsEnded = Objects.requireNonNull(serverRunsEnded, "serverRunsEnded");
        this.routes = List.of(
                routes.on(PlayingMessage.class, message -> playing(message.payload())),
                routes.on(ServerScriptsMessage.class, this::answered),
                routes.on(RelayFailedMessage.class, message -> {
                    // The refused message and its correlation name the request together.
                    if (message.messageId() == CompanionProtocol.SERVER_SCRIPTS_REQUEST) refused(message.correlation(), message.reason());
                }));
    }

    /**
     * A connection was established while the game plays {@code playing}, or null until it tells: what it told before the
     * connection was taken as established names the server to ask.
     */
    public synchronized void connected(PlayingPayload playing) {
        this.target = null;
        this.waiting = 0;
        this.compiler.accept("", ScriptCompilationService.NO_SERVER);
        if (playing != null) playing(playing);
    }

    /** The game disconnected; the compiler tells it itself. Nothing waits for an answer any more. */
    public synchronized void disconnected() {
        this.target = null;
        this.waiting = 0;
    }

    /** The game plays {@code playing}: asks its server where it is another one than asked last. */
    private synchronized void playing(PlayingPayload playing) {
        String target = switch (playing) {
            case PlayingPayload.Singleplayer singleplayer -> singleplayer.identity();
            case PlayingPayload.Multiplayer multiplayer when multiplayer.totalDebug() -> multiplayer.identity();
            default -> null;
        };
        if (Objects.equals(target, this.target)) return;
        if (this.target != null) this.serverRunsEnded.run();
        this.target = target;
        this.waiting = 0;
        if (target == null) {
            this.compiler.accept("", ScriptCompilationService.NO_SERVER);
            return;
        }
        this.compiler.accept("", "Waiting for the server");
        int request = --this.lastRequest;
        this.waiting = request;
        if (!this.admitted.getAsBoolean() || !this.sender.send(new ServerScriptsRequestMessage(request), request, target)) {
            // The next PLAYING asks again.
            this.target = null;
            this.waiting = 0;
            this.compiler.accept("", "Minecraft disconnected");
        }
    }

    /** The server answered; an answer to an earlier question may still arrive after the game moved on. */
    private synchronized void answered(ServerScriptsMessage message) {
        if (this.waiting == 0 || message.request() != this.waiting) return;
        this.waiting = 0;
        this.compiler.accept(this.target, message.refusal());
    }

    /** The game client could not carry request {@code correlation} to the server. */
    private synchronized void refused(int correlation, String reason) {
        if (this.waiting == 0 || correlation != this.waiting) return;
        this.waiting = 0;
        this.compiler.accept("", reason);
    }

    @Override
    public void close() {
        this.routes.forEach(Runnable::run);
    }
}
