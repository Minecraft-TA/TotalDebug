package com.github.minecraft_ta.totaldebug.client.input;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.neoforged.neoforge.client.event.ScreenEvent;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/** Holds the one-press latch for the F6 world and GUI code-view input. */
public final class CodeViewInput {
    private final Consumer<Optional<Class<?>>> openTarget;
    private boolean worldKeyWasDown;
    private Screen screenHoldingKey;

    public CodeViewInput(Consumer<Optional<Class<?>>> openTarget) {
        this.openTarget = Objects.requireNonNull(openTarget, "openTarget");
    }

    public void onClientTick(Minecraft minecraft, KeyMapping keyMapping) {
        boolean worldKeyIsDown = minecraft.screen == null && keyMapping.isDown();
        if (worldKeyIsDown && !this.worldKeyWasDown) {
            this.openTarget.accept(CodeTargetResolver.resolveWorldTarget(minecraft));
        }
        this.worldKeyWasDown = worldKeyIsDown;
    }

    public void onScreenKeyPressed(
            Minecraft minecraft,
            KeyMapping keyMapping,
            ScreenEvent.KeyPressed.Pre event
    ) {
        if (!keyMapping.matches(event.getKeyCode(), event.getScanCode())) {
            return;
        }
        if (this.screenHoldingKey == event.getScreen()) {
            event.setCanceled(true);
            return;
        }
        this.screenHoldingKey = event.getScreen();

        Optional<Class<?>> targetClass = Optional.empty();
        if (event.getScreen() instanceof AbstractContainerScreen<?> containerScreen) {
            var slot = containerScreen.getSlotUnderMouse();
            if (slot != null && slot.hasItem()) {
                targetClass = CodeTargetResolver.resolveItemTarget(minecraft, slot.getItem());
            }
        }
        this.openTarget.accept(targetClass);
        event.setCanceled(true);
    }

    public void onScreenKeyReleased(KeyMapping keyMapping, ScreenEvent.KeyReleased.Pre event) {
        if (!keyMapping.matches(event.getKeyCode(), event.getScanCode())) {
            return;
        }
        if (this.screenHoldingKey == event.getScreen()) {
            this.screenHoldingKey = null;
        }
        event.setCanceled(true);
    }
}
