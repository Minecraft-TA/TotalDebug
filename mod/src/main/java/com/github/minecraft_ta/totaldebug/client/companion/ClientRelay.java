package com.github.minecraft_ta.totaldebug.client.companion;

import com.github.minecraft_ta.totaldebug.network.RelayAssembler;
import com.github.minecraft_ta.totaldebug.network.RelayChunk;
import com.github.minecraft_ta.totaldebug.network.ToServerPayload;
import com.github.minecraft_ta.totaldebug.protocol.relay.RelayedMessage;
import com.github.minecraft_ta.totaldebug.protocol.relay.RelayedMessages;
import com.github.minecraft_ta.totaldebug.protocol.scnet.CompanionLeftMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * The game client's end of the relay (see {@code docs/MOD_SIDES.md}): carries Companion's messages for the server there
 * and the server's back, without reading them. It checks only the envelope: the game session a message is valid in, and
 * whether the server has TotalDebug. The one message it writes itself tells the server that Companion left.
 */
public final class ClientRelay {
    private final CompanionAppClient companionApp;
    private final Supplier<String> gameSession;
    private final RelayAssembler fromServer = RelayAssembler.toClient();

    /** {@code gameSession} is the joined world's session, or null before the player inspected anything in it. */
    public ClientRelay(CompanionAppClient companionApp, Supplier<String> gameSession) {
        this.companionApp = Objects.requireNonNull(companionApp, "companionApp");
        this.gameSession = Objects.requireNonNull(gameSession, "gameSession");
    }

    /** Carries a message Companion addressed to the server. Client thread. */
    public void toServer(RelayedMessage message) {
        if (!message.gameSession().isEmpty() && !message.gameSession().equals(this.gameSession.get())) {
            this.companionApp.sendRelayFailed(message.correlation(), "The world containing this target was left; inspect it again");
            return;
        }
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null) {
            this.companionApp.sendRelayFailed(message.correlation(), "Join a world to reach its server");
            return;
        }
        if (!connection.hasChannel(ToServerPayload.TYPE)) {
            this.companionApp.sendRelayFailed(message.correlation(), "The server does not have TotalDebug");
            return;
        }
        send(message);
    }

    /**
     * Companion's connection closed: the server ends the manifest session Companion asked it for, and the runs in it,
     * rather than keep them running for no one. Client thread.
     */
    public void companionLeft() {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null || !connection.hasChannel(ToServerPayload.TYPE)) return;
        send(RelayedMessages.toServer(new CompanionLeftMessage(), 0, ""));
    }

    private static void send(RelayedMessage message) {
        for (RelayChunk chunk : RelayChunk.split(message.messageId(), message.body(), RelayChunk.TO_SERVER_BYTES)) {
            PacketDistributor.sendToServer(new ToServerPayload(chunk));
        }
    }

    /** Takes one piece of a message the server sent Companion. */
    public void fromServer(RelayChunk chunk) {
        this.fromServer.accept(chunk).ifPresent(message ->
                this.companionApp.sendFromServer(new RelayedMessage(0, "", message.messageId(), message.body())));
    }

    /** The player left the server: what it was sending is dropped, and Companion hears the server is gone. */
    public void serverLeft() {
        this.fromServer.clear();
        this.companionApp.sendServerLeft();
    }
}
