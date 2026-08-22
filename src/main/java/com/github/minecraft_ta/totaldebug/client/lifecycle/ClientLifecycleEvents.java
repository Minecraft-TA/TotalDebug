package com.github.minecraft_ta.totaldebug.client.lifecycle;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.client.TotalDebugClient;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

@EventBusSubscriber(modid = TotalDebug.MOD_ID, value = Dist.CLIENT)
public final class ClientLifecycleEvents {
    private ClientLifecycleEvents() {
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> TotalDebugClient.initialize(Minecraft.getInstance()));
    }
}
