package com.github.minecraft_ta.totaldebug.handler;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.network.TickTimeRequestMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public class TabOverlayRenderHandler {

    private long timeStamp;

    @SubscribeEvent
    public void onOverlayRender(RenderGameOverlayEvent.Post event) {
        if (event.getType() != RenderGameOverlayEvent.ElementType.PLAYER_LIST) {
            return;
        }

        requestTickTime();
    }

    @SubscribeEvent
    public void onOverlayRenderSingleplayer(RenderGameOverlayEvent.Post event) {
        if (event.getType() != RenderGameOverlayEvent.ElementType.CHAT) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.gameSettings.keyBindPlayerList.isKeyDown() && mc.isIntegratedServerRunning()) {
            mc.ingameGUI.getTabList().renderPlayerlist(new ScaledResolution(mc).getScaledWidth(), mc.world.getScoreboard(), null);
            requestTickTime();
        }
    }

    private void requestTickTime() {
        if ((System.currentTimeMillis() - timeStamp) >= 500) {
            TotalDebug.INSTANCE.network.sendToServer(new TickTimeRequestMessage());
            timeStamp = System.currentTimeMillis();
        }
    }

}