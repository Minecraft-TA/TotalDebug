package com.github.minecraft_ta.totaldebug.nei.serializer;

import codechicken.nei.recipe.ICraftingHandler;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.item.ItemStack;

import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

public abstract class AbstractRecipeHandlerSerializer {
    private boolean finished = false;
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Serializes all recipes for the given handler using the given items.
     *
     * @param handler The handler to serialize recipes for
     * @param items   The items to serialize recipes
     * @return A map of all recipes for the given items
     */
    public abstract Map<ItemStack, List<IRecipeSerializer>> serializeRecipes(ICraftingHandler handler, Collection<ItemStack> items);

    public Map<ItemStack, List<IRecipeSerializer>> loadRecipes(ICraftingHandler handler, Collection<ItemStack> items) {
        this.lock.lock();
        try {
            if (!this.finished) {
                Map<ItemStack, List<IRecipeSerializer>> recipes = this.serializeRecipes(handler, items);
                this.finished = true;
                return recipes;
            }
        } finally {
            this.lock.unlock();
        }
        return Collections.emptyMap();
    }

    public Set<ItemStack> discoverItems(List<IRecipeSerializer> serializers, Collection<ItemStack> items) {
        Set<ItemStack> newItems = new ObjectOpenHashSet<>();
        for (IRecipeSerializer serializer : serializers) {
            serializer.discoverItems(items, newItems);
        }
        return newItems;
    }

    public boolean isFinished() {
        return this.finished;
    }

    public void reset() {
        this.finished = false;
    }

}
