package com.github.minecraft_ta.totaldebug.server.script;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

@EventBusSubscriber(modid = TotalDebug.MOD_ID)
final class ServerScriptLifecycleEvents {
    private ServerScriptLifecycleEvents() {
    }

    @SubscribeEvent
    static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            TotalDebug.get().serverScripts().removePlayer(player);
        }
    }
}
