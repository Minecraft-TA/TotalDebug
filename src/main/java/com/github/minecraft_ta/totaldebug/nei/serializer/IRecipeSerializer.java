package com.github.minecraft_ta.totaldebug.nei.serializer;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.Fluid;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

public interface IRecipeSerializer {

    void writeRecipe(DataOutputStream out, Object2IntMap<ItemStack> itemStackLookup, Map<Fluid, Integer> fluidLookup) throws IOException;

    void discoverItems(Collection<ItemStack> items, Set<ItemStack> newItems);

    String getRecipeType();

}
