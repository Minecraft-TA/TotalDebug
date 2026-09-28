package com.github.minecraft_ta.totaldebug.client.world;

import com.github.minecraft_ta.totaldebug.network.ToServerPayload;
import com.github.minecraft_ta.totaldebug.protocol.message.PlayingPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.storage.LevelResource;

/** What the game plays, as Companion is told it: its menu, a singleplayer world or a server. See {@code docs/GAME_LOCATION.md}. */
public final class Playing {
    private Playing() {
    }

    /** Client thread only. */
    public static PlayingPayload capture() {
        Minecraft minecraft = Minecraft.getInstance();
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
                connection.hasChannel(ToServerPayload.TYPE), Math.clamp(player.getPermissionLevel(), 0, 4));
    }
}
