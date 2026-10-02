package com.github.minecraft_ta.totaldebug.client.companion;

import com.github.minecraft_ta.totaldebug.network.RelayAssembler;
import com.github.minecraft_ta.totaldebug.network.RelayChunk;
import com.github.minecraft_ta.totaldebug.network.ToServerPayload;
import com.github.minecraft_ta.totaldebug.protocol.relay.RelayedMessage;
import com.github.minecraft_ta.totaldebug.protocol.relay.RelayedMessages;
import com.github.minecraft_ta.totaldebug.protocol.scnet.CompanionLeftMessage;
import com.github.minecraft_ta.totaldebug.protocol.scnet.FromServerMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * The game client's end of the relay (see {@code docs/MOD_SIDES.md}): carries Companion's messages for the server there
 * and the server's back, without reading them. It checks only the envelope: the world a message is valid in, and
 * whether the server has TotalDebug. The one message it writes itself tells the server that Companion left.
 */
public final class ClientRelay {
    private final CompanionAppClient companionApp;
    private final Supplier<String> world;
    private final RelayAssembler fromServer = RelayAssembler.toClient();
    /** The Companion connection whose replies {@link #fromServer} is putting together. Client thread. */
    private int assembling;

    /** {@code world} is the world the game last told Companion it plays, as {@code PLAYING} names it. */
    public ClientRelay(CompanionAppClient companionApp, Supplier<String> world) {
        this.companionApp = Objects.requireNonNull(companionApp, "companionApp");
        this.world = Objects.requireNonNull(world, "world");
    }

    /**
     * Carries a message Companion connection {@code companion} addressed to the server; a message from an earlier
     * connection is dropped. Client thread.
     */
    public void toServer(int companion, RelayedMessage message) {
        if (companion != this.companionApp.companionConnection()) return;
        if (!message.world().isEmpty() && !message.world().equals(this.world.get())) {
            this.companionApp.sendRelayFailed(companion, message, "The game left the world this was meant for");
            return;
        }
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null) {
            this.companionApp.sendRelayFailed(companion, message, "Join a world to reach its server");
            return;
        }
        if (!connection.hasChannel(ToServerPayload.TYPE)) {
            this.companionApp.sendRelayFailed(companion, message, "The server does not have TotalDebug");
            return;
        }
        send(companion, message);
    }

    /**
     * Companion connection {@code companion} closed: the server ends its runs rather than keep them running for no one.
     * Client thread.
     */
    public void companionLeft(int companion) {
        this.fromServer.clear();
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null || !connection.hasChannel(ToServerPayload.TYPE)) return;
        send(companion, RelayedMessages.toServer(new CompanionLeftMessage(), 0, ""));
    }

    private static void send(int companion, RelayedMessage message) {
        for (RelayChunk chunk : RelayChunk.split(companion, message.messageId(), message.body(), RelayChunk.TO_SERVER_BYTES)) {
            PacketDistributor.sendToServer(new ToServerPayload(chunk));
        }
    }

    /**
     * Takes one piece of a message the server sent Companion. An answer to an earlier Companion connection is dropped:
     * the connected Companion counts its run ids from the beginning again. So is one the game handles after it left the
     * world, which Companion would take for the next world's.
     */
    public void fromServer(RelayChunk chunk) {
        if (chunk.companion() != this.companionApp.companionConnection()) return;
        if (this.world.get().isEmpty()) return;
        if (chunk.companion() != this.assembling) {
            // What was put together for an earlier connection would otherwise hold the budget.
            this.fromServer.clear();
            this.assembling = chunk.companion();
        }
        this.fromServer.accept(chunk).ifPresent(message -> this.companionApp.send(chunk.companion(), new FromServerMessage(
                new RelayedMessage(0, "", message.messageId(), message.body()))));
    }

    /** The player left the server: what it was sending is dropped. */
    public void serverLeft() {
        this.fromServer.clear();
    }
}
