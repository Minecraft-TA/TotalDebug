package com.github.minecraft_ta.totaldebug.handler;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;

public class BossBarHandler {

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onBossBarRender(RenderGameOverlayEvent.Pre event) {
        if (event.type == RenderGameOverlayEvent.ElementType.BOSSHEALTH && !TotalDebug.INSTANCE.config.renderBossBar) {
            event.setCanceled(true);
        }
    }
}
