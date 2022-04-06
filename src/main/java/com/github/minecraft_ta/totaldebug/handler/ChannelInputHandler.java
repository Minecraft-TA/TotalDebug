package com.github.minecraft_ta.totaldebug.handler;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import io.netty.channel.ChannelPipeline;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;

public class ChannelInputHandler {

    @SubscribeEvent
    public void onLoggIn(FMLNetworkEvent.ClientConnectedToServerEvent event) {
        ChannelPipeline pipeline = event.getManager().channel().pipeline();
        pipeline.addBefore("packet_handler", "listener", TotalDebug.PROXY.getPackerLogger());
    }

    @SubscribeEvent
    public void onFMLNetworkClientDisconnectionFromServer(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        ChannelPipeline pipeline = event.getManager().channel().pipeline();
        pipeline.remove(TotalDebug.PROXY.getPackerLogger());
    }
}
