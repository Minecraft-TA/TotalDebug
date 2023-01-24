package com.github.minecraft_ta.totaldebug.nei.serialization;

import codechicken.nei.recipe.ICraftingHandler;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.item.crafting.ShapedRecipes;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.*;

public class ShapedCraftingSerializer extends AbstractRecipeHandlerSerializer {

    /**
     * Serializes all shaped crafting recipes we only to do this once
     * <p>
     * {@inheritDoc}
     */
    @Override
    boolean loadRecipesImpl(ICraftingHandler handler, Collection<ItemStack> items, Map<ItemStack, List<IRecipe>> recipes, Set<ItemStack> newItems) {
        // We only need to do this once
        for (Object o : CraftingManager.getInstance().getRecipeList()) {
            if (o instanceof ShapedRecipes) {
                ShapedRecipes recipe = (ShapedRecipes) o;
                // Check if any itemstacks are already discovered and if not, add them to the newItems list
                if (!items.contains(recipe.getRecipeOutput())) {
                    newItems.add(recipe.getRecipeOutput());
                }

                for (ItemStack recipeItem : recipe.recipeItems) {
                    if (recipeItem != null && !items.contains(recipeItem)) {
                        newItems.add(recipeItem);
                    }
                }

                // Add the recipe to the map
                recipes.computeIfAbsent(recipe.getRecipeOutput(),
                        k -> new ArrayList<>()).add(new ShapedRecipe(recipe.recipeWidth,
                        recipe.recipeHeight,
                        Collections.singletonList(recipe.recipeItems)));
            }
        }

        return true;
    }


    class ShapedRecipe extends BaseRecipe {

        private final int width;
        private final int height;

        public ShapedRecipe(int width, int height, List<ItemStack[]> inputs) {
            super("shaped", inputs);
            this.width = width;
            this.height = height;
        }

        @Override
        public void writeRecipe(DataOutputStream out, Object2IntMap<ItemStack> itemStackLookup) throws IOException {
            out.writeInt(width);
            out.writeInt(height);

            for (ItemStack itemStack : getInputs().get(0)) {
                if (itemStack == null) {
                    out.writeInt(-1);
                } else {
                    out.writeInt(itemStackLookup.getInt(itemStack));
                }
            }
        }
    }


}
