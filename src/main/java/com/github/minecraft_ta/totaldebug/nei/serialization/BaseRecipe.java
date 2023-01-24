package com.github.minecraft_ta.totaldebug.nei.serialization;

import net.minecraft.item.ItemStack;

import java.util.List;

public abstract class BaseRecipe implements IRecipe {

    private final String recipeType;
    private final List<ItemStack[]> inputs;

    public BaseRecipe(String recipeType, List<ItemStack[]> inputs) {
        this.recipeType = recipeType;
        this.inputs = inputs;
    }

    public String getRecipeType() {
        return recipeType;
    }

    public List<ItemStack[]> getInputs() {
        return inputs;
    }
}
