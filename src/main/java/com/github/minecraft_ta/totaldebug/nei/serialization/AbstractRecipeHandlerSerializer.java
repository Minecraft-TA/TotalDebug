package com.github.minecraft_ta.totaldebug.nei.serialization;

import codechicken.nei.recipe.ICraftingHandler;
import net.minecraft.item.ItemStack;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

public abstract class AbstractRecipeHandlerSerializer {

    private static boolean finished = false;

    public void loadRecipes(ICraftingHandler handler, Collection<ItemStack> items, Map<ItemStack, List<IRecipe>> recipes, Set<ItemStack> newItems) {
        if (!finished) {
            finished = loadRecipesImpl(handler, items, recipes, newItems);
        }
    }

    /**
     * Serializes all recipes for the given handler using the given items.
     *
     * @param handler  The handler to serialize recipes for
     * @param items    The items to serialize recipes
     * @param recipes  The map to add the recipes to
     * @param newItems Newly discovered items for further processing
     * @return True if all recipes have been serialized, false if more processing is required
     */
    abstract boolean loadRecipesImpl(ICraftingHandler handler, Collection<ItemStack> items, Map<ItemStack, List<IRecipe>> recipes, Set<ItemStack> newItems);

}
