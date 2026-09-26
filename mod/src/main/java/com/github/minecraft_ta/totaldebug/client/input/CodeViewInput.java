package com.github.minecraft_ta.totaldebug.client.input;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.neoforged.neoforge.client.event.ScreenEvent;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Holds the one-press latch for F6: inspect the targeted block or entity, or in a screen the hovered stack. A stack in
 * a slot is inspected live; one shown by a recipe viewer opens its item's definition.
 */
public final class CodeViewInput {
    private static final ScreenItemStackResolver NO_SCREEN_ITEM = (screen, mouseX, mouseY) -> Optional.empty();

    private final Consumer<Optional<Selection>> inspect;
    private final AtomicReference<ScreenItemStackResolver> screenItemResolver = new AtomicReference<>(NO_SCREEN_ITEM);
    private boolean worldKeyWasDown;
    private Screen screenHoldingKey;

    public CodeViewInput(Consumer<Optional<Selection>> inspect) {
        this.inspect = Objects.requireNonNull(inspect, "inspect");
    }

    public void onClientTick(Minecraft minecraft, KeyMapping keyMapping) {
        boolean worldKeyIsDown = minecraft.screen == null && keyMapping.isDown();
        if (worldKeyIsDown && !this.worldKeyWasDown) {
            this.inspect.accept(CodeTargetResolver.resolveWorldTarget(minecraft));
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
        Screen screen = event.getScreen();
        this.inspect.accept(resolveScreenTarget(
                this.screenItemResolver.get(),
                screen,
                mouseX,
                mouseY,
                () -> slotUnderMouse(screen).flatMap(slot -> CodeTargetResolver.resolveSlotTarget(minecraft, screen, slot))
        ));
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

    /** A recipe viewer's hovered stack opens its item's definition; otherwise the stack in the slot under the mouse. */
    static Optional<Selection> resolveScreenTarget(
            ScreenItemStackResolver viewerResolver,
            Screen screen,
            double mouseX,
            double mouseY,
            Supplier<Optional<Selection>> slotTarget
    ) {
        return viewerResolver.resolve(screen, mouseX, mouseY).map(CodeTargetResolver::definition).or(slotTarget);
    }

    private static Optional<Slot> slotUnderMouse(Screen screen) {
        if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) {
            return Optional.empty();
        }
        Slot slot = containerScreen.getSlotUnderMouse();
        return slot != null && slot.hasItem() ? Optional.of(slot) : Optional.empty();
    }
}
