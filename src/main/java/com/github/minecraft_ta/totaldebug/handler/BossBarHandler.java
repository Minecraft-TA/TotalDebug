package com.github.minecraft_ta.totaldebug.handler;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public class BossBarHandler {

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onBossBarRender(RenderGameOverlayEvent.BossInfo event) {
        if (!TotalDebug.INSTANCE.config.renderBossBar) {
            event.setCanceled(true);
        }
    }
}
