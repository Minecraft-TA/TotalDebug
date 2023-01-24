package com.github.minecraft_ta.totaldebug.nei.serialization;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.item.ItemStack;

import java.io.DataOutputStream;
import java.io.IOException;

public interface IRecipe {

    void writeRecipe(DataOutputStream out, Object2IntMap<ItemStack> itemStackLookup) throws IOException;

    String getRecipeType();

}
