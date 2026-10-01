package com.github.minecraft_ta.totaldebug.client.lifecycle;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.client.TotalDebugClient;
import com.github.minecraft_ta.totaldebug.client.resource.ResourceReloads;
import com.github.minecraft_ta.totaldebug.protocol.Side;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.packs.PackSelectionScreen;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * The client's lifecycle: its setup, its resource reloads, joining and leaving a world, and the moments its packs
 * change. What Companion is told about what the game plays and the packs in effect follows these; nothing is polled.
 */
@EventBusSubscriber(modid = TotalDebug.MOD_ID, value = Dist.CLIENT)
public final class ClientLifecycleEvents {
    private ClientLifecycleEvents() {
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> TotalDebugClient.initialize(Minecraft.getInstance()));
    }

    /** Runs as each client resource reload applies, on the client thread. */
    @SubscribeEvent
    static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) manager -> {
            ResourceReloads.reloaded();
            TotalDebugClient.current().ifPresent(TotalDebugClient::resourcesReloaded);
        });
    }

    /** After the level and the player exist, before the first tick in the world. */
    @SubscribeEvent
    static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        TotalDebugClient.current().ifPresent(TotalDebugClient::joined);
    }

    /** Before the game tears the level down, which for a singleplayer world waits for its server to stop. */
    @SubscribeEvent
    static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        TotalDebugClient.current().ifPresent(TotalDebugClient::left);
        TotalDebug.get().tickTasks().clear(Side.CLIENT);
    }

    /**
     * A screen that changed key bindings, as the game's or a mod's controls screen, has Companion told. The Resource
     * Packs screen rescans the pack folders while it is open; closing it without changing the selection starts no
     * resource reload, so the packs it found are told here. It selected the packs before it closes.
     */
    @SubscribeEvent
    static void onScreenClosing(ScreenEvent.Closing event) {
        TotalDebugClient.current().ifPresent(TotalDebugClient::screenClosing);
        if (!(event.getScreen() instanceof PackSelectionScreen)) return;
        ResourceReloads.selected();
        TotalDebugClient.current().ifPresent(TotalDebugClient::packsChanged);
    }
}
