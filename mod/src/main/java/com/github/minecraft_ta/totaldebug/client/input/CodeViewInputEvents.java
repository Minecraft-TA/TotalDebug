package com.github.minecraft_ta.totaldebug.client.input;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.client.TotalDebugClient;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid = TotalDebug.MOD_ID, value = Dist.CLIENT)
final class CodeViewInputEvents {
    private static final KeyMapping OPEN_CODE_VIEW = new KeyMapping(
            "key.total_debug.open_code_gui",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F6,
            "key.categories.total_debug"
    );

    private CodeViewInputEvents() {
    }

    @SubscribeEvent
    static void registerKeyMapping(RegisterKeyMappingsEvent event) {
        event.register(OPEN_CODE_VIEW);
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        TotalDebugClient.current().ifPresent(client ->
                client.codeViewInput().onClientTick(Minecraft.getInstance(), OPEN_CODE_VIEW)
        );
    }

    @SubscribeEvent
    static void onScreenKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        TotalDebugClient.current().ifPresent(client ->
                client.codeViewInput().onScreenKeyPressed(Minecraft.getInstance(), OPEN_CODE_VIEW, event)
        );
    }

    @SubscribeEvent
    static void onScreenKeyReleased(ScreenEvent.KeyReleased.Pre event) {
        TotalDebugClient.current().ifPresent(client ->
                client.codeViewInput().onScreenKeyReleased(OPEN_CODE_VIEW, event)
        );
    }
}
