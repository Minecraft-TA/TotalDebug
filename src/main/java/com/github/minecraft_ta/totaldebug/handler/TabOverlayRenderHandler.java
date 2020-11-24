package com.github.minecraft_ta.totaldebug.handler;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.network.TickTimeRequestMessage;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public class TabOverlayRenderHandler {

    private long timeStamp;

    @SubscribeEvent
    public void onOverlayRender(RenderGameOverlayEvent.Post event) {
        if (event.isCanceled() || event.getType() != RenderGameOverlayEvent.ElementType.PLAYER_LIST) {
            return;
        }

        if ((System.currentTimeMillis() - timeStamp) >= 500) {
            TotalDebug.INSTANCE.network.sendToServer(new TickTimeRequestMessage());
            timeStamp = System.currentTimeMillis();
            System.out.println("Send a packet");
        }
    }
}