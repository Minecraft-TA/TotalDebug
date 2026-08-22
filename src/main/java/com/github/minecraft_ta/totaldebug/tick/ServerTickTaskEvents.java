package com.github.minecraft_ta.totaldebug.tick;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@EventBusSubscriber(modid = TotalDebug.MOD_ID)
final class ServerTickTaskEvents {
    private ServerTickTaskEvents() {
    }

    @SubscribeEvent
    static void onServerTickPre(ServerTickEvent.Pre event) {
        TotalDebug.get().tickTasks().drain(TickDomain.SERVER, TickPhase.PRE);
    }

    @SubscribeEvent
    static void onServerTickPost(ServerTickEvent.Post event) {
        TotalDebug.get().tickTasks().drain(TickDomain.SERVER, TickPhase.POST);
    }

    @SubscribeEvent
    static void onServerStopped(ServerStoppedEvent event) {
        TotalDebug.get().tickTasks().clear(TickDomain.SERVER);
    }
}
