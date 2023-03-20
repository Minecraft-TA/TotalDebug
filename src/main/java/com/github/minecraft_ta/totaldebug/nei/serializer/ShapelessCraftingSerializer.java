package com.github.minecraft_ta.totaldebug.nei.serializer;

import codechicken.nei.recipe.ICraftingHandler;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.ShapelessRecipes;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.oredict.OreDictionary;
import net.minecraftforge.oredict.ShapelessOreRecipe;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.*;

public class ShapelessCraftingSerializer extends AbstractRecipeHandlerSerializer {

    @Override
    public Map<ItemStack, List<IRecipeSerializer>> serializeRecipes(ICraftingHandler handler, Collection<ItemStack> items) {
        Map<ItemStack, List<IRecipeSerializer>> recipes = new HashMap<>();
        for (Object o : CraftingManager.getInstance().getRecipeList()) {
            if (o instanceof IRecipe) {
                IRecipe recipe = (IRecipe) o;
                IRecipeSerializer serializer = null;
                if (recipe instanceof ShapelessRecipes) {
                    serializer = new ShapelessRecipeSerializer((ShapelessRecipes) recipe);
                } else if (recipe instanceof ShapelessOreRecipe) {
                    serializer = new ShapelessOreRecipeSerializer((ShapelessOreRecipe) recipe);
                }
                if (serializer != null) {
                    recipes.computeIfAbsent(recipe.getRecipeOutput(), itemStack -> new ArrayList<>()).add(serializer);
                }
            }
        }

        return recipes;
    }

    static class ShapelessRecipeSerializer implements IRecipeSerializer {

        private final ShapelessRecipes recipe;

        public ShapelessRecipeSerializer(ShapelessRecipes recipe) {
            this.recipe = recipe;
        }

        @Override
        public void writeRecipe(DataOutputStream out, Object2IntMap<ItemStack> itemStackLookup, Map<Fluid, Integer> fluidLookup) throws IOException {
            out.writeInt(recipe.getRecipeOutput().stackSize);
            out.writeInt(recipe.recipeItems.size());
            for (Object recipeItem : recipe.recipeItems) {
                if (recipeItem instanceof ItemStack) {
                    ItemStack itemStack = (ItemStack) recipeItem;
                    out.writeInt(itemStackLookup.getInt(itemStack));
                }
            }
        }

        @Override
        public void discoverItems(Collection<ItemStack> items, Set<ItemStack> newItems) {
            ItemStack stack = recipe.getRecipeOutput();
            if (!items.contains(stack))
                newItems.add(stack);

            for (Object recipeItem : recipe.recipeItems) {
                if (recipeItem instanceof ItemStack) {
                    ItemStack itemStack = (ItemStack) recipeItem;
                    if (!items.contains(itemStack)) {
                        newItems.add(itemStack);
                    }
                }
            }
        }

        @Override
        public String getRecipeType() {
            return "shapeless";
        }

    }

    static class ShapelessOreRecipeSerializer implements IRecipeSerializer {

        private final ShapelessOreRecipe recipe;

        public ShapelessOreRecipeSerializer(ShapelessOreRecipe recipe) {
            this.recipe = recipe;
        }

        @Override
        public void writeRecipe(DataOutputStream out, Object2IntMap<ItemStack> itemStackLookup, Map<Fluid, Integer> fluidLookup) throws IOException {
            out.writeInt(recipe.getRecipeOutput().stackSize);
            out.writeInt(recipe.getInput().size());
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

                    // Edge case when the item uses blocks as they get oredict WILDCARD_VALUE as the metadata
                    if (itemStack.getItemDamage() == OreDictionary.WILDCARD_VALUE) {
                        itemStack = ItemStack.copyItemStack(itemStack);
                        itemStack.setItemDamage(0);
                    }

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
            return "shapeless_ore";
        }
    }

}
