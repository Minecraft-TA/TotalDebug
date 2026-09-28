package com.github.minecraft_ta.totaldebug.client.world;

import com.github.minecraft_ta.totaldebug.network.RunServerScriptPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.PlayingPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Tells Companion what the game plays: its menu, a singleplayer world or a server, whenever that changes or Companion
 * connects again. Checked once a second on the client thread. See {@code docs/GAME_LOCATION.md}.
 */
public final class PlayingPublisher {
    private static final int CHECK_TICKS = 20;

    private final Consumer<PlayingPayload> publish;
    private PlayingPayload published;
    private int ticks;

    public PlayingPublisher(Consumer<PlayingPayload> publish) {
        this.publish = Objects.requireNonNull(publish, "publish");
    }

    /** Publishes again at the next check, such as for a newly connected Companion. */
    public synchronized void republish() {
        this.published = null;
        this.ticks = CHECK_TICKS;
    }

    /** Client thread only. */
    public void tick() {
        synchronized (this) {
            if (++this.ticks < CHECK_TICKS) return;
            this.ticks = 0;
        }
        PlayingPayload current = capture(Minecraft.getInstance());
        synchronized (this) {
            if (current.equals(this.published)) return;
            this.published = current;
        }
        this.publish.accept(current);
    }

    private static PlayingPayload capture(Minecraft minecraft) {
        ClientPacketListener connection = minecraft.getConnection();
        LocalPlayer player = minecraft.player;
        if (minecraft.level == null || connection == null || player == null) return new PlayingPayload.Menu();
        IntegratedServer server = minecraft.getSingleplayerServer();
        if (server != null) {
            return new PlayingPayload.Singleplayer(server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().toString(),
                    server.isPublished());
        }
        ServerData data = minecraft.getCurrentServer();
        return new PlayingPayload.Multiplayer(data == null ? "" : data.ip, data != null && data.isRealm(),
                connection.hasChannel(RunServerScriptPayload.TYPE), Math.clamp(player.getPermissionLevel(), 0, 4));
    }
}
