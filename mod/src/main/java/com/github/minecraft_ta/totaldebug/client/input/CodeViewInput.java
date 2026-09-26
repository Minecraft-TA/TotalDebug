package com.github.minecraft_ta.totaldebug.client.input;

import com.github.minecraft_ta.totaldebug.client.inspection.KeptStacks;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.ScreenEvent;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Holds the one-press latch for F6: inspect the targeted block or entity, or in a screen the hovered stack, wherever it
 * is shown: a slot, the creative lists or a recipe viewer. The stack is kept as it is when F6 is pressed.
 */
public final class CodeViewInput {
    private static final ScreenItemStackResolver NO_SCREEN_ITEM = (screen, mouseX, mouseY) -> Optional.empty();

    private final Consumer<Optional<Selection>> inspect;
    private final KeptStacks kept;
    private final AtomicReference<ScreenItemStackResolver> screenItemResolver = new AtomicReference<>(NO_SCREEN_ITEM);
    private boolean worldKeyWasDown;
    private Screen screenHoldingKey;

    public CodeViewInput(Consumer<Optional<Selection>> inspect, KeptStacks kept) {
        this.inspect = Objects.requireNonNull(inspect, "inspect");
        this.kept = Objects.requireNonNull(kept, "kept");
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
        ScreenItemStackResolver viewer = this.screenItemResolver.get();
        this.inspect.accept(slotFirst(() -> slotStack(screen), () -> viewer.resolve(screen, mouseX, mouseY))
                .map(stack -> CodeTargetResolver.stack(this.kept, stack)));
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

    /**
     * The stack in the slot under the mouse, otherwise the one a recipe viewer shows there. The slot is asked first
     * because a recipe viewer also reports slots, as ingredients it can look up.
     */
    static <T> Optional<T> slotFirst(Supplier<Optional<T>> slot, Supplier<Optional<T>> viewer) {
        return slot.get().or(viewer);
    }

    private static Optional<ItemStack> slotStack(Screen screen) {
        if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) {
            return Optional.empty();
        }
        Slot slot = containerScreen.getSlotUnderMouse();
        return slot != null && slot.hasItem() ? Optional.of(slot.getItem()) : Optional.empty();
    }
}
