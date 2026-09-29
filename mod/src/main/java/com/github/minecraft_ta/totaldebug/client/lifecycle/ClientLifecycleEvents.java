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
import net.neoforged.neoforge.event.TagsUpdatedEvent;

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
     * The server's tags arrived after its data loaded or reloaded, as {@code /reload} and {@code /datapack} do. In
     * singleplayer the server fires this event too, on its own thread; only the client's copy counts.
     */
    @SubscribeEvent
    static void onTagsUpdated(TagsUpdatedEvent event) {
        if (event.getUpdateCause() != TagsUpdatedEvent.UpdateCause.CLIENT_PACKET_RECEIVED) return;
        TotalDebugClient.current().ifPresent(TotalDebugClient::packsChanged);
    }

    /**
     * The Resource Packs screen rescans the pack folders while it is open. Closing it without changing the selection
     * starts no resource reload, so the packs it found are told here.
     */
    @SubscribeEvent
    static void onScreenClosing(ScreenEvent.Closing event) {
        if (!(event.getScreen() instanceof PackSelectionScreen)) return;
        TotalDebugClient.current().ifPresent(TotalDebugClient::packsChanged);
    }
}
