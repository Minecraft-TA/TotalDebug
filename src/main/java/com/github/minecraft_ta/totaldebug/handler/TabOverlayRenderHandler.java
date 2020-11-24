package com.github.minecraft_ta.totaldebug.handler;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.network.TickTimeRequestMessage;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public class TabOverlayRenderHandler {

    @SubscribeEvent
    public void onOverlayRender(RenderGameOverlayEvent.Post event) {
        if (event.isCanceled() || event.getType() != RenderGameOverlayEvent.ElementType.PLAYER_LIST) {
            return;
        }
        //TODO: Only send a packet a few times per second
        TotalDebug.INSTANCE.network.sendToServer(new TickTimeRequestMessage());
    }
}