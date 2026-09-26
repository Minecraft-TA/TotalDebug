package com.github.minecraft_ta.totaldebug.script;

import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectRef;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Finds the stacks {@link SubjectRef.Stack} subjects name on one side. A resolution takes the stack in the named slot
 * and later ones follow that same stack object while the player keeps it in their inventory, the open container or
 * under the cursor, so it is still found after it moved. Once it was split off, used up or dropped, the slot names the
 * subject again: what it holds now is followed, as a block that was replaced, and an empty slot is reported.
 */
public final class HeldStacks {
    private final Map<SubjectRef.Stack, WeakReference<ItemStack>> followed = new ConcurrentHashMap<>();

    public ScriptTarget.HeldStack resolve(Player player, SubjectRef.Stack subject) {
        Objects.requireNonNull(player, "player");
        WeakReference<ItemStack> reference = this.followed.get(subject);
        ItemStack stack = reference == null ? null : reference.get();
        if (stack == null || stack.isEmpty() || !kept(player, stack)) {
            stack = inSlot(player, subject);
            if (stack.isEmpty()) {
                this.followed.remove(subject);
                throw new IllegalStateException(reference == null ? "The slot is empty"
                        : "The stack is no longer in the inventory or the open container, and its slot is empty");
            }
            this.followed.put(subject, new WeakReference<>(stack));
        }
        return new ScriptTarget.HeldStack(stack, player, subject, player.level());
    }

    private static ItemStack inSlot(Player player, SubjectRef.Stack subject) {
        if (subject.menu() == SubjectRef.Stack.INVENTORY) {
            Inventory inventory = player.getInventory();
            if (subject.slot() >= inventory.getContainerSize()) {
                throw new IllegalStateException("The inventory has no slot " + subject.slot());
            }
            return inventory.getItem(subject.slot());
        }
        AbstractContainerMenu menu = player.containerMenu;
        if (menu.containerId != subject.menu()) {
            throw new IllegalStateException("The container the stack was selected in is closed");
        }
        if (subject.slot() >= menu.slots.size()) {
            throw new IllegalStateException("The open container has no slot " + subject.slot());
        }
        return menu.getSlot(subject.slot()).getItem();
    }

    /** Whether {@code stack} is this very object in the player's inventory, the open container or under the cursor. */
    private static boolean kept(Player player, ItemStack stack) {
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot) == stack) return true;
        }
        AbstractContainerMenu menu = player.containerMenu;
        for (int slot = 0; slot < menu.slots.size(); slot++) {
            if (menu.getSlot(slot).getItem() == stack) return true;
        }
        return menu.getCarried() == stack;
    }
}
