package com.github.minecraft_ta.totaldebug.server;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.network.RelayAssembler;
import com.github.minecraft_ta.totaldebug.network.RelayChunk;
import com.github.minecraft_ta.totaldebug.network.ToCompanionPayload;
import com.github.minecraft_ta.totaldebug.protocol.relay.RelayedMessage;
import com.github.minecraft_ta.totaldebug.protocol.relay.RelayedMessages;
import com.github.tth05.scnet.message.AbstractMessage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * The server's end of the relay (see {@code docs/MOD_SIDES.md}): puts together the Companion messages players' clients
 * carry to it, hands each to its server operation, and sends the answers back to that player's client in pieces.
 */
public final class ServerRelay {
    private final Map<UUID, RelayAssembler> assemblers = new ConcurrentHashMap<>();
    private volatile ServerOperations operations;

    /** Hands the server's messages to {@code operations}. */
    public void handle(ServerOperations operations) {
        this.operations = Objects.requireNonNull(operations, "operations");
    }

    /** Takes one piece a player's client carried. Server thread. */
    public void receive(ServerPlayer player, RelayChunk chunk) {
        this.assemblers.computeIfAbsent(player.getUUID(), id -> RelayAssembler.toServer()).accept(chunk).ifPresent(message -> {
            AbstractMessage decoded;
            try {
                decoded = RelayedMessages.decodeToServer(new RelayedMessage(0, "", message.messageId(), message.body()));
            } catch (RuntimeException invalid) {
                TotalDebug.LOGGER.warn("Discarding a relayed message {} from {} that could not be read", message.messageId(),
                        player.getGameProfile().getName(), invalid);
                return;
            }
            ServerOperations handler = this.operations;
            if (handler == null) {
                TotalDebug.LOGGER.warn("Discarding a relayed message {} before the server's operations are ready", message.messageId());
                return;
            }
            handler.handle(player, decoded);
        });
    }

    /** Whether {@code player}'s client takes messages from the server's TotalDebug. */
    public static boolean reaches(ServerPlayer player) {
        return player.connection.hasChannel(ToCompanionPayload.TYPE);
    }

    /**
     * Sends {@code message} to Companion through {@code player}'s client, if that player is still connected. Encodes on
     * the calling thread and sends on the server thread.
     */
    public void send(MinecraftServer server, ServerPlayer player, AbstractMessage message) {
        send(server, player, message, () -> true);
    }

    /** As {@link #send(MinecraftServer, ServerPlayer, AbstractMessage)}, dropped unless {@code current} still holds then. */
    public void send(MinecraftServer server, ServerPlayer player, AbstractMessage message, BooleanSupplier current) {
        RelayedMessage relayed = RelayedMessages.fromServer(message);
        List<RelayChunk> chunks = RelayChunk.split(relayed.messageId(), relayed.body(), RelayChunk.TO_CLIENT_BYTES);
        server.execute(() -> {
            if (!current.getAsBoolean() || server.getPlayerList().getPlayer(player.getUUID()) != player || !reaches(player)) return;
            for (RelayChunk chunk : chunks) player.connection.send(new ToCompanionPayload(chunk));
        });
    }

    /** Forgets what {@code player}'s client was carrying. */
    public void removePlayer(ServerPlayer player) {
        this.assemblers.remove(player.getUUID());
    }

    /** Forgets every player's transfers, such as when the server stops. */
    public void clear() {
        this.assemblers.clear();
    }
}
