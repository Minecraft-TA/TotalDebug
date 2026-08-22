package com.github.minecraft_ta.totaldebug.client;

import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid = TotalDebug.MOD_ID, value = Dist.CLIENT)
final class CodeViewKeyHandler {
    private static final KeyMapping OPEN_CODE_VIEW = new KeyMapping(
            "key.total_debug.open_code_gui",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_F6,
            "key.categories.total_debug"
    );
    private static boolean worldKeyWasDown;
    private static Screen screenHoldingKey;

    private CodeViewKeyHandler() {
    }

    @SubscribeEvent
    static void registerKeyMapping(RegisterKeyMappingsEvent event) {
        event.register(OPEN_CODE_VIEW);
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        boolean worldKeyIsDown = minecraft.screen == null && OPEN_CODE_VIEW.isDown();
        if (worldKeyIsDown && !worldKeyWasDown) {
            CodeTargetResolver.resolveWorldTarget(minecraft)
                    .ifPresentOrElse(
                            TotalDebugClientRuntime.decompilation()::openClass,
                            () -> TotalDebugClientRuntime.decompilation().focusCompanion()
                    );
        }
        worldKeyWasDown = worldKeyIsDown;
    }

    @SubscribeEvent
    static void onScreenKeyPressed(ScreenEvent.KeyPressed.Pre event) {
        if (!OPEN_CODE_VIEW.matches(event.getKeyCode(), event.getScanCode())) {
            return;
        }

        if (screenHoldingKey == event.getScreen()) {
            event.setCanceled(true);
            return;
        }
        screenHoldingKey = event.getScreen();

        if (event.getScreen() instanceof AbstractContainerScreen<?> containerScreen) {
            var slot = containerScreen.getSlotUnderMouse();
            if (slot != null && slot.hasItem()) {
                CodeTargetResolver.resolveItemTarget(Minecraft.getInstance(), slot.getItem())
                        .ifPresentOrElse(
                                TotalDebugClientRuntime.decompilation()::openClass,
                                () -> TotalDebugClientRuntime.decompilation().focusCompanion()
                        );
            } else {
                TotalDebugClientRuntime.decompilation().focusCompanion();
            }
        } else {
            TotalDebugClientRuntime.decompilation().focusCompanion();
        }
        event.setCanceled(true);
    }

    @SubscribeEvent
    static void onScreenKeyReleased(ScreenEvent.KeyReleased.Pre event) {
        if (!OPEN_CODE_VIEW.matches(event.getKeyCode(), event.getScanCode())) {
            return;
        }
        if (screenHoldingKey == event.getScreen()) {
            screenHoldingKey = null;
        }
        event.setCanceled(true);
    }
}
