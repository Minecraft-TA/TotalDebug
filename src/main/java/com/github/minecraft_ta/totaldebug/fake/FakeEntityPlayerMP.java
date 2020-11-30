package com.github.minecraft_ta.totaldebug.fake;

import com.mojang.authlib.GameProfile;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.EnumPacketDirection;
import net.minecraft.network.NetworkManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.PlayerInteractionManager;
import net.minecraft.stats.RecipeBookServer;
import net.minecraft.world.GameType;
import net.minecraft.world.WorldServer;


@SuppressWarnings("EntityConstructor")
public class FakeEntityPlayerMP extends EntityPlayerMP {

    public FakeEntityPlayerMP(MinecraftServer server, WorldServer worldIn, GameProfile profile, PlayerInteractionManager interactionManagerIn) {
        super(server, worldIn, profile, interactionManagerIn);
    }

    public static FakeEntityPlayerMP spawn(String name, MinecraftServer server, double x, double y, double z, float yaw, float pitch, int dimension) {
        GameProfile gameProfile = server.getPlayerProfileCache().getGameProfileForUsername(name);
        WorldServer world = server.getWorld(dimension);
        PlayerInteractionManager interactionManager = new PlayerInteractionManager(world);

        FakeEntityPlayerMP fakePlayer = new FakeEntityPlayerMP(server, world, gameProfile, interactionManager);
        fakePlayer.setPositionAndRotation(x, y, z, yaw, pitch);

        FakeNetworkManager netManager = new FakeNetworkManager(EnumPacketDirection.CLIENTBOUND);
        server.getPlayerList().initializeConnectionToPlayer(netManager, fakePlayer, new FakeNetHandlerPlayServer(server, netManager, fakePlayer));

        fakePlayer.setHealth(20);
        fakePlayer.setVelocity(0, 0, 0);
        fakePlayer.isDead = false;
        fakePlayer.stepHeight = 0.6F;
        interactionManager.setGameType(GameType.SURVIVAL);

        return fakePlayer;
    }

    @Override
    public RecipeBookServer getRecipeBook() {
        return new FakeRecipeBookServer();
    }
}
