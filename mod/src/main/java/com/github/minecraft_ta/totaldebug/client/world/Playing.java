package com.github.minecraft_ta.totaldebug.client.world;

import com.github.minecraft_ta.totaldebug.network.ToServerPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.PlayingPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;

import java.util.Objects;

/** What the game plays, as Companion is told it: its menu, a singleplayer world or a server. See {@code docs/GAME_LOCATION.md}. */
public final class Playing {
    private Playing() {
    }

    /** The singleplayer world or the server the player just joined, at {@code LoggingIn}. Client thread only. */
    public static PlayingPayload joined() {
        Minecraft minecraft = Minecraft.getInstance();
        IntegratedServer server = minecraft.getSingleplayerServer();
        if (server != null) {
            return new PlayingPayload.Singleplayer(server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().toString());
        }
        ClientPacketListener connection = Objects.requireNonNull(minecraft.getConnection(), "connection");
        ServerData data = minecraft.getCurrentServer();
        return new PlayingPayload.Multiplayer(data == null ? "" : data.ip, data != null && data.isRealm(),
                connection.hasChannel(ToServerPayload.TYPE));
    }
}
