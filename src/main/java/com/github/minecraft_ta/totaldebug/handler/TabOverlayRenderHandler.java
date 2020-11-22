package com.github.minecraft_ta.totaldebug.handler;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiPlayerTabOverlay;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public class TabOverlayRenderHandler {

    @SubscribeEvent
    public void onOverlayRender(RenderGameOverlayEvent.Post event) {
        if (event.isCanceled() || event.getType() != RenderGameOverlayEvent.ElementType.PLAYER_LIST) {
            return;
        }

        Minecraft minecraft = Minecraft.getMinecraft();
        GuiPlayerTabOverlay tabList = minecraft.ingameGUI.getTabList();
        long[] tickTimeArray = minecraft.getIntegratedServer().tickTimeArray;

        double meanTickTime = Math.round((mean(tickTimeArray) * 1.0E-6D) * 10.0) / 10.0;
        double meanTPS = Math.min(1000 / meanTickTime, 20);

        tabList.setHeader(new TextComponentString("MSPT: " + meanTickTime + " TPS: " + meanTPS));
    }

    private long mean(long[] values) {
        long sum = 0L;
        for (long v : values) {
            sum += v;
        }
        return sum / values.length;
    }

}