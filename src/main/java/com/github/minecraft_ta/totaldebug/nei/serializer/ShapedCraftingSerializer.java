package com.github.minecraft_ta.totaldebug.nei.serializer;

import codechicken.nei.recipe.ICraftingHandler;
import cpw.mods.fml.relauncher.ReflectionHelper;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.ShapedRecipes;
import net.minecraftforge.oredict.ShapedOreRecipe;

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
    boolean loadRecipesImpl(ICraftingHandler handler, Collection<ItemStack> items, Map<ItemStack, List<IRecipeSerializer>> recipes, Set<ItemStack> newItems) {
        // We only need to do this once
        for (Object o : CraftingManager.getInstance().getRecipeList()) {
            if (o instanceof IRecipe) {
                IRecipe recipe = (IRecipe) o;
                IRecipeSerializer serializer = null;
                if (recipe instanceof ShapedRecipes) {
                    serializer = new ShapedRecipeSerializer((ShapedRecipes) recipe);
                } else if (recipe instanceof ShapedOreRecipe) {
                    serializer = new ShapedOreRecipeSerializer((ShapedOreRecipe) recipe);
                }
                if (serializer != null) {
                    recipes.computeIfAbsent(recipe.getRecipeOutput(), itemStack -> new ArrayList<>()).add(serializer);
                    serializer.discoverItems(items, newItems);
                }
            }
        }

        return true;
    }


    static class ShapedRecipeSerializer implements IRecipeSerializer {

        private final ShapedRecipes recipe;

        public ShapedRecipeSerializer(ShapedRecipes recipe) {
            this.recipe = recipe;
        }

        @Override
        public void writeRecipe(DataOutputStream out, Object2IntMap<ItemStack> itemStackLookup) throws IOException {
            out.writeInt(recipe.recipeWidth);
            out.writeInt(recipe.recipeHeight);

            for (ItemStack itemStack : recipe.recipeItems) {
                if (itemStack == null) {
                    out.writeInt(-1);
                } else {
                    out.writeInt(itemStackLookup.getInt(itemStack));
                }
            }
        }

        @Override
        public void discoverItems(Collection<ItemStack> items, Set<ItemStack> newItems) {
            // Discover all items in the recipe
            ItemStack recipeOutput = recipe.getRecipeOutput();
            if (!items.contains(recipeOutput))
                newItems.add(recipeOutput);

            for (ItemStack itemStack : recipe.recipeItems) {
                if (itemStack != null && !items.contains(itemStack))
                    newItems.add(itemStack);
            }
        }

        @Override
        public String getRecipeType() {
            return "shaped";
        }
    }

    static class ShapedOreRecipeSerializer implements IRecipeSerializer {

        private final ShapedOreRecipe recipe;

        ShapedOreRecipeSerializer(ShapedOreRecipe recipe) {
            this.recipe = recipe;
        }

        @Override
        public void writeRecipe(DataOutputStream out, Object2IntMap<ItemStack> itemStackLookup) throws IOException {
            int width = ReflectionHelper.getPrivateValue(ShapedOreRecipe.class, recipe, "width");
            int height = ReflectionHelper.getPrivateValue(ShapedOreRecipe.class, recipe, "height");

            out.writeInt(width);
            out.writeInt(height);

            // Write mirrored
            out.writeBoolean(ReflectionHelper.getPrivateValue(ShapedOreRecipe.class, recipe, "mirrored"));

            // Write input
            for (Object o : recipe.getInput()) {
                if (o instanceof ItemStack) {
                    ItemStack itemStack = (ItemStack) o;
                    out.writeInt(1);
                    out.writeInt(itemStackLookup.getInt(itemStack));
                } else if (o instanceof List) {
                    List<ItemStack> itemStacks = (List<ItemStack>) o;
                    out.writeInt(itemStacks.size());
                    for (ItemStack itemStack : itemStacks) {
                        out.writeInt(itemStackLookup.getInt(itemStack));
                    }
                } else {
                    out.writeInt(0);
                }
            }
        }

        @Override
        public void discoverItems(Collection<ItemStack> items, Set<ItemStack> newItems) {
            // Discover all items in the recipe
            ItemStack recipeOutput = recipe.getRecipeOutput();
            if (!items.contains(recipeOutput))
                newItems.add(recipeOutput);

            for (Object o : recipe.getInput()) {
                if (o instanceof ItemStack) {
                    ItemStack itemStack = (ItemStack) o;
                    if (!items.contains(itemStack))
                        newItems.add(itemStack);
                } else if (o instanceof List) {
                    List<ItemStack> itemStacks = (List<ItemStack>) o;
                    for (ItemStack itemStack : itemStacks) {
                        if (!items.contains(itemStack))
                            newItems.add(itemStack);
                    }
                }
            }

        }

        @Override
        public String getRecipeType() {
            return "shaped_ore";
        }
    }


}
