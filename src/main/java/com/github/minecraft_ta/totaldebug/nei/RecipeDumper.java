package com.github.minecraft_ta.totaldebug.nei;

import codechicken.core.CommonUtils;
import codechicken.nei.ItemList;
import codechicken.nei.NEIClientUtils;
import codechicken.nei.api.API;
import codechicken.nei.config.DataDumper;
import codechicken.nei.recipe.GuiCraftingRecipe;
import codechicken.nei.recipe.ICraftingHandler;
import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.integration.NotEnoughItemIntegration;
import com.github.minecraft_ta.totaldebug.nei.serializer.AbstractRecipeHandlerSerializer;
import com.github.minecraft_ta.totaldebug.nei.serializer.IRecipeSerializer;
import com.github.minecraft_ta.totaldebug.nei.serializer.RecipeHandlerSerializerFactory;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import it.unimi.dsi.fastutil.Hash;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenCustomHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenCustomHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenCustomHashSet;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;

import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class RecipeDumper extends DataDumper {

    public static final Hash.Strategy<ItemStack> STRATEGY = new Hash.Strategy<ItemStack>() {
        @Override
        public int hashCode(ItemStack o) {
            return Objects.hash(o.getItem(), o.getItemDamage(), o.getTagCompound());
        }

        @Override
        public boolean equals(ItemStack a, ItemStack b) {
            return a.getItem() == b.getItem() && a.getItemDamage() == b.getItemDamage() && ItemStack.areItemStackTagsEqual(a, b);
        }
    };
    private boolean initialized;

    public RecipeDumper(String name) {
        super(name);
    }

    @Override
    public String[] header() {
        return null;
    }

    @Override
    public Iterable<String[]> dump(int mode) {
        return null;
    }

    private void dumpRecipes() {
        CompletableFuture.runAsync(() -> {
            Map<ItemStack, List<IRecipeSerializer>> itemStackListMap = loadRecipes(ItemList.items, new ObjectOpenCustomHashSet<>(STRATEGY));

            // Create a lookup map for the itemstacks
            Object2IntMap<ItemStack> itemStackLookup = new Object2IntOpenCustomHashMap<>(STRATEGY);
            int i = 0;
            for (Map.Entry<ItemStack, List<IRecipeSerializer>> itemStackListEntry : itemStackListMap.entrySet()) {
                itemStackLookup.put(itemStackListEntry.getKey(), i++);
                // Remove ItemStacks that have no recipes
                if (itemStackListEntry.getValue().isEmpty())
                    itemStackListMap.remove(itemStackListEntry.getKey());
            }

            File outputFile = new File(CommonUtils.getMinecraftDir(), "dumps/recipe-export.bin");
            try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(outputFile.toPath()))) {
                // Write the itemstack lookup
                out.writeInt(itemStackLookup.size());
                for (Map.Entry<ItemStack, Integer> entry : itemStackLookup.entrySet()) {
                    out.writeInt(entry.getValue());
                    out.writeUTF(entry.getKey().getUnlocalizedName());
                    out.writeUTF(entry.getKey().getDisplayName());
                    out.writeInt(entry.getKey().getItemDamage());
                    out.writeUTF(String.valueOf(entry.getKey().getTagCompound()));
                }

                // Write fluid lookup
                Map<Fluid, Integer> fluidLookup = FluidRegistry.getRegisteredFluidIDsByFluid();
                out.writeInt(fluidLookup.size());
                for (Map.Entry<Fluid, Integer> entry : fluidLookup.entrySet()) {
                    out.writeInt(entry.getValue());
                    out.writeUTF(entry.getKey().getName());
                }

                // Write the recipes
                out.writeInt(itemStackListMap.size());

                for (Map.Entry<ItemStack, List<IRecipeSerializer>> itemStackListEntry : itemStackListMap.entrySet()) {
                    // Write the itemstack id
                    out.writeInt(itemStackLookup.getInt(itemStackListEntry.getKey()));

                    // Write the recipes
                    out.writeInt(itemStackListEntry.getValue().size());

                    for (IRecipeSerializer iRecipe : itemStackListEntry.getValue()) {
                        // Write the recipe type
                        out.writeUTF(iRecipe.getRecipeType());

                        // Write the recipe
                        iRecipe.writeRecipe(out, itemStackLookup, fluidLookup);
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
                TotalDebug.LOGGER.info(e.getMessage());
            }

            TotalDebug.LOGGER.info("Recipe dump complete!");
            // Reset the recipe serializers
            RecipeHandlerSerializerFactory.reset();
        }).exceptionally(throwable -> {
            throwable.printStackTrace();
            TotalDebug.LOGGER.info(throwable.getMessage());
            return null;
        });
    }


    private Map<ItemStack, List<IRecipeSerializer>> loadRecipes(Collection<ItemStack> items, Set<ItemStack> oldItems) {
        Map<ItemStack, List<IRecipeSerializer>> recipes = new Object2ObjectOpenCustomHashMap<>(STRATEGY);
        Set<ItemStack> newItems = new ObjectOpenCustomHashSet<>(STRATEGY);

        for (ICraftingHandler craftinghandler : GuiCraftingRecipe.craftinghandlers) {
            AbstractRecipeHandlerSerializer recipeHandlerSerializer = RecipeHandlerSerializerFactory.getRecipeHandlerSerializer(craftinghandler.getClass());
            if (recipeHandlerSerializer != null) {
                recipeHandlerSerializer.loadRecipes(craftinghandler, items, recipes, newItems);
            }
        }

        // Get potential recipes for the new items
        if (!newItems.isEmpty()) {
            // Add to recipes for the lookup table later
            for (ItemStack newItem : newItems) {
                recipes.computeIfAbsent(newItem, k -> new ArrayList<>());
            }

            oldItems.addAll(items);

            // Merge the new recipes into the list of existing recipes
            Map<ItemStack, List<IRecipeSerializer>> recipeMap = loadRecipes(newItems, oldItems);
            for (Map.Entry<ItemStack, List<IRecipeSerializer>> entry : recipeMap.entrySet()) {
                recipes.get(entry.getKey()).addAll(entry.getValue());
            }
        }

        return recipes;
    }

    @Override
    public void mouseClicked(int mousex, int mousey, int button) {
        if (dumpButtonSize().contains(mousex, mousey)) {
            NEIClientUtils.playClickSound();
            dumpRecipes();
        }
    }

    @SubscribeEvent
    public void onTickPlayerTick(TickEvent.PlayerTickEvent event) {
        if (!initialized && NotEnoughItemIntegration.getInstance().isEnabled() && event.side.isClient()) {
            API.addOption(this);
            initialized = true;
        }
    }


}
