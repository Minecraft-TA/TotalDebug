package com.github.minecraft_ta.totaldebug.client.input;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.ScreenEvent;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Holds the one-press latch for the F6 world and GUI code-view input. */
public final class CodeViewInput {
    private static final ScreenItemStackResolver NO_SCREEN_ITEM = (screen, mouseX, mouseY) -> Optional.empty();

    private final Consumer<Optional<Class<?>>> openTarget;
    private final AtomicReference<ScreenItemStackResolver> screenItemResolver = new AtomicReference<>(NO_SCREEN_ITEM);
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

        double mouseX = minecraft.mouseHandler.xpos()
                * minecraft.getWindow().getGuiScaledWidth()
                / minecraft.getWindow().getScreenWidth();
        double mouseY = minecraft.mouseHandler.ypos()
                * minecraft.getWindow().getGuiScaledHeight()
                / minecraft.getWindow().getScreenHeight();
        Optional<Class<?>> targetClass = resolveHoveredItem(
                this.screenItemResolver.get(),
                event.getScreen(),
                mouseX,
                mouseY,
                () -> containerItem(event.getScreen())
        ).flatMap(itemStack -> CodeTargetResolver.resolveItemTarget(minecraft, itemStack));
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

    public void installScreenItemResolver(ScreenItemStackResolver resolver) {
        Objects.requireNonNull(resolver, "resolver");
        if (!this.screenItemResolver.compareAndSet(NO_SCREEN_ITEM, resolver)) {
            throw new IllegalStateException("A screen item resolver is already installed");
        }
    }

    public void removeScreenItemResolver(ScreenItemStackResolver resolver) {
        Objects.requireNonNull(resolver, "resolver");
        if (!this.screenItemResolver.compareAndSet(resolver, NO_SCREEN_ITEM)) {
            throw new IllegalStateException("The screen item resolver being removed is not installed");
        }
    }

    static Optional<ItemStack> resolveHoveredItem(
            ScreenItemStackResolver screenResolver,
            Screen screen,
            double mouseX,
            double mouseY,
            Supplier<Optional<ItemStack>> containerItem
    ) {
        return screenResolver.resolve(screen, mouseX, mouseY).or(containerItem);
    }

    private static Optional<ItemStack> containerItem(Screen screen) {
        if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) {
            return Optional.empty();
        }

        var slot = containerScreen.getSlotUnderMouse();
        return slot != null && slot.hasItem() ? Optional.of(slot.getItem()) : Optional.empty();
    }
}
