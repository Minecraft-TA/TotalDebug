package com.github.minecraft_ta.totaldebug.nei.serializer;

import codechicken.nei.recipe.ICraftingHandler;
import gregtech.api.util.GT_Recipe;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.*;

public class GTRecipeSerializer extends AbstractRecipeHandlerSerializer {

    @Override
    boolean loadRecipesImpl(ICraftingHandler handler, Collection<ItemStack> items, Map<ItemStack, List<IRecipeSerializer>> recipes, Set<ItemStack> newItems) {
        for (GT_Recipe.GT_Recipe_Map recipeMap : GT_Recipe.GT_Recipe_Map.sMappings) {
            for (GT_Recipe recipe : recipeMap.mRecipeList) {
                GregtechRecipe serializer = new GregtechRecipe(recipe, recipeMap.mUnlocalizedName);
                ItemStack[] outputs = recipe.mOutputs;
                for (ItemStack output : outputs) {
                    if (output == null) continue;
                    recipes.computeIfAbsent(output, itemStack -> new ArrayList<>()).add(serializer);
                }
                serializer.discoverItems(items, newItems);
            }
        }
        return true;
    }


    static class GregtechRecipe implements IRecipeSerializer {

        private final GT_Recipe recipe;
        private final String name;

        GregtechRecipe(GT_Recipe recipe, String name) {
            this.recipe = recipe;
            this.name = name;
        }

        @Override
        public void writeRecipe(DataOutputStream out, Object2IntMap<ItemStack> itemStackLookup, Map<Fluid, Integer> fluidLookup) throws IOException {
            ItemStack[] inputs = recipe.mInputs;
            out.writeInt(inputs.length);
            for (ItemStack input : inputs) {
                if (input == null) continue; // Empty slots should not play a role in the recipe I guess
                out.writeInt(itemStackLookup.getInt(input));
                out.writeInt(input.stackSize);
            }
            ItemStack[] outputs = recipe.mOutputs;
            out.writeInt(outputs.length);
            for (ItemStack output : outputs) {
                if (output == null) continue;
                out.writeInt(itemStackLookup.getInt(output));
                out.writeInt(output.stackSize);
            }

            // Fluids
            FluidStack[] fluidInputs = recipe.mFluidInputs;
            out.writeInt(fluidInputs.length);
            for (FluidStack fluidInput : fluidInputs) {
                out.writeInt(fluidLookup.get(fluidInput.getFluid()));
                out.writeInt(fluidInput.amount);
            }

            FluidStack[] fluidOutputs = recipe.mFluidOutputs;
            out.writeInt(fluidOutputs.length);
            for (FluidStack fluidOutput : fluidOutputs) {
                out.writeInt(fluidLookup.get(fluidOutput.getFluid()));
                out.writeInt(fluidOutput.amount);
            }
        }

        @Override
        public void discoverItems(Collection<ItemStack> items, Set<ItemStack> newItems) {
            for (ItemStack input : recipe.mInputs) {
                if (input != null && !items.contains(input)) {
                    newItems.add(input);
                    items.add(input);
                }
            }
            for (ItemStack output : recipe.mOutputs) {
                if (output != null && !items.contains(output)) {
                    newItems.add(output);
                    items.add(output);
                }
            }
        }

        @Override
        public String getRecipeType() {
            return this.name;
        }
    }

}
