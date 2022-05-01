package com.github.minecraft_ta.totaldebug.handler;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import io.netty.channel.ChannelPipeline;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;

import java.util.HashMap;
import java.util.UUID;

public class ChannelInputHandler {

    public static final HashMap<UUID, PacketBlocker> packetBlockers = new HashMap<>();
    private boolean initialized;

    @SubscribeEvent
    public void onLoggIn(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        ChannelPipeline pipeline = event.getManager().channel().pipeline();
        pipeline.addBefore("packet_handler", "listener", TotalDebug.PROXY.getPackerLogger());
    }

    @SubscribeEvent
    public void onFMLNetworkClientDisconnectionFromServer(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        ChannelPipeline pipeline = event.getManager().channel().pipeline();
        pipeline.remove(TotalDebug.PROXY.getPackerLogger());
        this.initialized = false;
    }

    @SubscribeEvent
    public void onLogginServer(FMLNetworkEvent.ServerConnectionFromClientEvent event) {
        ChannelPipeline pipeline = event.getManager().channel().pipeline();
        PacketBlocker packetBlocker = new PacketBlocker();
        packetBlockers.put(((NetHandlerPlayServer) event.getHandler()).player.getUniqueID(), packetBlocker);
        pipeline.addBefore("packet_handler", "packet_blocker", packetBlocker);
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        packetBlockers.remove(event.player.getUniqueID());
    }

    @SubscribeEvent
    public void onTickPlayerTick(TickEvent.PlayerTickEvent event) {
        if (!this.initialized && event.side.isClient()) {
            this.initialized = true;
            TotalDebug.INSTANCE.config.syncBlockedPackets();
        }
    }

}
