package com.github.minecraft_ta.totaldebug.server.world;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;

@EventBusSubscriber(modid = TotalDebug.MOD_ID)
final class WorldDatapackEvents {
    private WorldDatapackEvents() {
    }

    /** The world's data loaded or reloaded, as {@code /reload} and {@code /datapack} do, and goes to its players now. */
    @SubscribeEvent
    static void onDatapackSync(OnDatapackSyncEvent event) {
        TotalDebug.get().worldDatapacks().loaded(event.getRelevantPlayers());
    }
}
