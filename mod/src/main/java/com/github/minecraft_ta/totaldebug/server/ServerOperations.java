package com.github.minecraft_ta.totaldebug.server;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.protocol.scnet.CompanionLeftMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ManifestRequestMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.RunScriptMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.ServerSourceRequestMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.StopScriptMessage;
import com.github.minecraft_ta.totaldebug.server.script.ServerScriptService;
import com.github.tth05.scnet.message.AbstractMessage;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * What the server does with each message Companion sends it through the relay (see {@code docs/MOD_SIDES.md}). A new
 * server operation adds its message here and in {@code RelayedMessages}.
 */
public final class ServerOperations {
    private final Map<Class<? extends AbstractMessage>, BiConsumer<ServerPlayer, AbstractMessage>> operations;

    public ServerOperations(ServerScriptService scripts) {
        Objects.requireNonNull(scripts, "scripts");
        this.operations = Map.of(
                ManifestRequestMessage.class, (player, message) -> scripts.sendManifest(player),
                ServerSourceRequestMessage.class, (player, message) -> scripts.requestSource(player, (ServerSourceRequestMessage) message),
                RunScriptMessage.class, (player, message) -> scripts.runScript(player, (RunScriptMessage) message),
                StopScriptMessage.class, (player, message) -> scripts.stopScript(player, ((StopScriptMessage) message).scriptId()),
                CompanionLeftMessage.class, (player, message) -> scripts.endSession(player));
    }

    /** Runs {@code message}'s operation for {@code player}. Server thread. */
    void handle(ServerPlayer player, AbstractMessage message) {
        BiConsumer<ServerPlayer, AbstractMessage> operation = this.operations.get(message.getClass());
        if (operation == null) {
            TotalDebug.LOGGER.warn("The server has no operation for {}", message.getClass().getSimpleName());
            return;
        }
        operation.accept(player, message);
    }
}
