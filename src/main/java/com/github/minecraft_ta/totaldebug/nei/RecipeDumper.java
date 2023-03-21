package com.github.minecraft_ta.totaldebug.nei;

import codechicken.core.CommonUtils;
import codechicken.lib.vec.Rectangle4i;
import codechicken.nei.ItemList;
import codechicken.nei.NEIClientUtils;
import codechicken.nei.api.API;
import codechicken.nei.config.DataDumper;
import codechicken.nei.recipe.GuiCraftingRecipe;
import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.integration.NotEnoughItemIntegration;
import com.github.minecraft_ta.totaldebug.nei.imageexport.ItemStackDumper;
import com.github.minecraft_ta.totaldebug.nei.serializer.AbstractRecipeHandlerSerializer;
import com.github.minecraft_ta.totaldebug.nei.serializer.IRecipeSerializer;
import com.github.minecraft_ta.totaldebug.nei.serializer.RecipeHandlerSerializerFactory;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import it.unimi.dsi.fastutil.Hash;
import it.unimi.dsi.fastutil.objects.*;
import net.minecraft.client.Minecraft;
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

    private static final int[] resolutions = new int[]{16, 32, 48, 64, 128, 256};

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
            Map<ItemStack, List<IRecipeSerializer>> recipes = loadRecipes(ItemList.items, new ObjectOpenCustomHashSet<>(STRATEGY));

            // Create a lookup map for the itemstacks
            Object2IntMap<ItemStack> itemStackLookup = new Object2IntOpenCustomHashMap<>(STRATEGY);
            int i = 0;
            for (Map.Entry<ItemStack, List<IRecipeSerializer>> itemStackListEntry : recipes.entrySet()) {
                itemStackLookup.put(itemStackListEntry.getKey(), i++);
                // Remove ItemStacks that have no recipes
                if (itemStackListEntry.getValue().isEmpty()) recipes.remove(itemStackListEntry.getKey());
            }

            // Export images for the itemstacks if the user wants to
            if (getMode() == 1) {
                Minecraft.getMinecraft().displayGuiScreen(new ItemStackDumper(this, new ArrayList<>(itemStackLookup.keySet()), getRes()));
            }

            File outputFile = new File(CommonUtils.getMinecraftDir(), "dumps/recipe-export.bin");
            try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(outputFile.toPath()))) {
                // Write the itemstack lookup
                out.writeInt(itemStackLookup.size());
                for (Map.Entry<ItemStack, Integer> entry : itemStackLookup.entrySet()) {
                    out.writeInt(entry.getValue());
                    ItemStack stack = entry.getKey();
                    writeUnlocalizedName(out, stack);
                    writeDisplayName(out, stack);
                    out.writeInt(stack.getItemDamage());
                    out.writeUTF(String.valueOf(stack.getTagCompound()));
                }

                // Write fluid lookup
                Map<Fluid, Integer> fluidLookup = FluidRegistry.getRegisteredFluidIDsByFluid();
                out.writeInt(fluidLookup.size());
                for (Map.Entry<Fluid, Integer> entry : fluidLookup.entrySet()) {
                    out.writeInt(entry.getValue());
                    out.writeUTF(entry.getKey().getName());
                }

                // Write the recipes
                out.writeInt(recipes.size());

                for (Map.Entry<ItemStack, List<IRecipeSerializer>> itemStackListEntry : recipes.entrySet()) {
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
            RecipeHandlerSerializerFactory.reset();
            return null;
        });
    }

    private void writeUnlocalizedName(DataOutputStream out, ItemStack stack) throws IOException {
        try {
            out.writeUTF(stack.getUnlocalizedName());
        } catch (Exception e) {
            // Try to write itemstack with damage 0 and no nbt instead
            try {
                TotalDebug.LOGGER.error("Failed to get unlocalized name for itemstack " + stack, e);
                out.writeUTF(new ItemStack(stack.getItem(), 1, 0).getUnlocalizedName());
            } catch (Exception e1) {
                TotalDebug.LOGGER.error("Failed to get unlocalized name for itemstack " + stack, e1);
                out.writeUTF("null");
            }
        }
    }

    private void writeDisplayName(DataOutputStream out, ItemStack stack) throws IOException {
        try {
            out.writeUTF(stack.getDisplayName());
        } catch (Exception e) {
            // Try to write itemstack with damage 0 and no nbt instead
            try {
                TotalDebug.LOGGER.error("Failed to get display name for itemstack " + stack, e);
                out.writeUTF(new ItemStack(stack.getItem(), 1, 0).getDisplayName());
            } catch (Exception e1) {
                TotalDebug.LOGGER.error("Failed to get display name for itemstack " + stack, e1);
                writeUnlocalizedName(out, stack);
            }
        }
    }


    private Map<ItemStack, List<IRecipeSerializer>> loadRecipes(Collection<ItemStack> items, Set<ItemStack> oldItems) {
        Object2ObjectMap<ItemStack, List<IRecipeSerializer>> allRecipes = new Object2ObjectOpenCustomHashMap<>(STRATEGY);
        ObjectSet<ItemStack> allItems = new ObjectOpenCustomHashSet<>(STRATEGY);

        // Load recipes and discover new items in parallel
        GuiCraftingRecipe.craftinghandlers.parallelStream().forEach(recipeHandler -> {
            AbstractRecipeHandlerSerializer handlerSerializer = RecipeHandlerSerializerFactory.getRecipeHandlerSerializer(recipeHandler.getClass());
            if (handlerSerializer != null && !handlerSerializer.isFinished()) {
                Map<ItemStack, List<IRecipeSerializer>> recipes = handlerSerializer.loadRecipes(recipeHandler, items);

                Set<ItemStack> newItems = new ObjectOpenCustomHashSet<>(STRATEGY);
                for (List<IRecipeSerializer> value : recipes.values()) {
                    newItems.addAll(handlerSerializer.discoverItems(value, oldItems));
                }

                // Merge the new recipes into the list of existing recipes
                synchronized (allRecipes) {
                    mergeRecipes(allRecipes, recipes);
                }

                synchronized (allItems) {
                    allItems.addAll(newItems);
                }
            }
        });

        // Get potential recipes for the new items
        if (!allItems.isEmpty()) {
            // Add to recipes for the lookup table later
            for (ItemStack newItem : allItems) {
                allRecipes.computeIfAbsent(newItem, k -> new ArrayList<>());
            }

            oldItems.addAll(items);

            // Merge the new recipes into the list of existing recipes
            mergeRecipes(allRecipes, loadRecipes(allItems, oldItems));
        }
        return allRecipes;
    }

    private void mergeRecipes(Map<ItemStack, List<IRecipeSerializer>> allRecipes, Map<ItemStack, List<IRecipeSerializer>> recipes) {
        for (Map.Entry<ItemStack, List<IRecipeSerializer>> entry : recipes.entrySet()) {
            allRecipes.merge(entry.getKey(), entry.getValue(), (oldRecipes, newRecipes) -> {
                oldRecipes.addAll(newRecipes);
                return oldRecipes;
            });
        }
    }

    @Override
    public void draw(int mousex, int mousey, float frame) {
        super.draw(mousex, mousey, frame);
        if (getMode() == 1) {
            int res = getRes();
            drawButton(mousex, mousey, resButtonSize(), res + "x" + res);
        }
    }

    @Override
    public String modeButtonText() {
        return translateN(name + ".mode." + getMode());
    }

    @Override
    public void mouseClicked(int mousex, int mousey, int button) {
        if (dumpButtonSize().contains(mousex, mousey)) {
            NEIClientUtils.playClickSound();
            dumpRecipes();
        } else if (modeCount() > 1 && modeButtonSize().contains(mousex, mousey)) {
            NEIClientUtils.playClickSound();
            getTag().setIntValue((getMode() + 1) % modeCount());
        } else if (getMode() == 1 && resButtonSize().contains(mousex, mousey)) {
            NEIClientUtils.playClickSound();
            getTag(name + ".res").setIntValue((renderTag(name + ".res").getIntValue(0) + 1) % resolutions.length);
        }
    }

    public int getRes() {
        int i = renderTag(name + ".res").getIntValue(0);
        if (i >= resolutions.length || i < 0) renderTag().setIntValue(i = 0);
        return resolutions[i];
    }

    public Rectangle4i resButtonSize() {
        int width = 50;
        return new Rectangle4i(modeButtonSize().x - width - 6, 0, width, 20);
    }

    @Override
    public int modeCount() {
        return 2;
    }

    @SubscribeEvent
    public void onTickPlayerTick(TickEvent.PlayerTickEvent event) {
        if (!initialized && NotEnoughItemIntegration.getInstance().isEnabled() && event.side.isClient()) {
            API.addOption(this);
            initialized = true;
        }
    }


}
