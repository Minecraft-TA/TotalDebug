package com.github.minecraft_ta.totaldebug.client.inspection;

import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The stacks selected with the inspect key, kept as copies of how they were when selected, so Companion can read them
 * afterwards. Selecting a stack identical to a kept one, with the same item, count and components, reuses its id, so
 * its page is the same. The newest {@value #CAPACITY} are kept.
 */
public final class KeptStacks {
    static final int CAPACITY = 64;

    private final Map<Long, ItemStack> stacks = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, ItemStack> eldest) {
            return size() > CAPACITY;
        }
    };
    private long next = 1;

    /** Keeps a copy of {@code stack}, or finds the identical one kept before; returns its id. */
    public synchronized long keep(ItemStack stack) {
        Long found = null;
        for (Map.Entry<Long, ItemStack> kept : this.stacks.entrySet()) {
            if (ItemStack.matches(kept.getValue(), stack)) {
                found = kept.getKey();
                break;
            }
        }
        if (found != null) {
            this.stacks.get(found);
            return found;
        }
        long id = this.next++;
        this.stacks.put(id, stack.copy());
        return id;
    }

    /** The kept stack with this id, unless newer selections replaced it. */
    public synchronized Optional<ItemStack> get(long id) {
        return Optional.ofNullable(this.stacks.get(id));
    }
}
