package com.github.minecraft_ta.totaldebug.server.world;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.entity.player.PermissionsChangedEvent;

@EventBusSubscriber(modid = TotalDebug.MOD_ID)
final class WorldDatapackEvents {
    private WorldDatapackEvents() {
    }

    /** A player's permission changes, such as by {@code /op}: whether they may change the world may have too. */
    @SubscribeEvent
    static void onPermissionsChanged(PermissionsChangedEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) TotalDebug.get().worldDatapacks().permissionChanged(player);
    }

    /** The world's data loaded or reloaded, as {@code /reload} and {@code /datapack} do, and goes to its players now. */
    @SubscribeEvent
    static void onDatapackSync(OnDatapackSyncEvent event) {
        TotalDebug.get().worldDatapacks().loaded(event.getRelevantPlayers());
    }
}
