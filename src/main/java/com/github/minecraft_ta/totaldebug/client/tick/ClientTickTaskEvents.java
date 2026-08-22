package com.github.minecraft_ta.totaldebug.client.tick;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.client.TotalDebugClient;
import com.github.minecraft_ta.totaldebug.tick.TickDomain;
import com.github.minecraft_ta.totaldebug.tick.TickPhase;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

@EventBusSubscriber(modid = TotalDebug.MOD_ID, value = Dist.CLIENT)
final class ClientTickTaskEvents {
    private ClientTickTaskEvents() {
    }

    @SubscribeEvent
    static void onClientTickPre(ClientTickEvent.Pre event) {
        TotalDebug.get().tickTasks().drain(TickDomain.CLIENT, TickPhase.PRE);
    }

    @SubscribeEvent
    static void onClientTickPost(ClientTickEvent.Post event) {
        TotalDebug.get().tickTasks().drain(TickDomain.CLIENT, TickPhase.POST);
    }

    @SubscribeEvent
    static void onClientLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        TotalDebugClient.get().stopAllScripts();
        TotalDebug.get().tickTasks().clear(TickDomain.CLIENT);
    }
}
