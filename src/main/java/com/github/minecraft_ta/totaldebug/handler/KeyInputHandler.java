package com.github.minecraft_ta.totaldebug.handler;

import codechicken.nei.api.API;
import codechicken.nei.guihook.GuiContainerManager;
import com.github.minecraft_ta.totaldebug.KeyBindings;
import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.companionApp.CompanionApp;
import com.github.minecraft_ta.totaldebug.companionApp.messages.FocusWindowMessage;
import com.github.minecraft_ta.totaldebug.integration.GregtechIntegration;
import com.github.minecraft_ta.totaldebug.util.BlockPos;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;
import cpw.mods.fml.relauncher.ReflectionHelper;
import gregtech.api.GregTech_API;
import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.inventory.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemMonsterPlacer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.world.World;
import net.minecraftforge.client.event.GuiScreenEvent;
import org.lwjgl.input.Keyboard;

import java.util.concurrent.CompletableFuture;

public class KeyInputHandler {

    private long lastRequested;

    @SubscribeEvent
    public void onKeyPress(InputEvent.KeyInputEvent event) {
        if (!KeyBindings.CODE_GUI.getIsKeyPressed())
            return;
        if (rayTraceEyes())
            return;

        CompletableFuture.runAsync(() -> {
            final CompanionApp companionApp = TotalDebug.PROXY.getCompanionApp();
            if (!companionApp.isConnected()) {
                companionApp.startAndConnect();
            } else {
                companionApp.getClient().getMessageProcessor().enqueueMessage(new FocusWindowMessage());
            }
        }).exceptionally((throwable) -> {
            throwable.printStackTrace();
            return null;
        });
    }

    @SubscribeEvent
    public void onGuiKeyPress(GuiScreenEvent.DrawScreenEvent.Post event) {
        if (!Keyboard.isKeyDown(KeyBindings.CODE_GUI.getKeyCode()))
            return;

        GuiScreen currentScreen = event.gui;
        if (currentScreen instanceof GuiContainer) {
            //Get the slot the mouse is hovering over from not enough items
            GuiContainer guiContainer = (GuiContainer) currentScreen;
            ItemStack stackMouseOver = GuiContainerManager.getStackMouseOver(guiContainer);
            if (stackMouseOver != null) {
                Item item = stackMouseOver.getItem();
                if (!checkForSpawnEggAndOpenGui(stackMouseOver)) {
                    handle(HitType.ITEM, null, Item.getIdFromItem(item), stackMouseOver.getItemDamage());
                }
                return;
            }

            Slot slot = ReflectionHelper.getPrivateValue(GuiContainer.class, guiContainer, "theSlot", "field_147006_u");
            if (slot != null && slot.getHasStack()) {
                ItemStack itemStack = slot.getStack();

                if (!checkForSpawnEggAndOpenGui(itemStack)) {
                    handle(HitType.ITEM, null, Item.itemRegistry.getIDForObject(itemStack.getItem()), itemStack.getItemDamage());
                }
            }
        }
    }

    private boolean checkForSpawnEggAndOpenGui(ItemStack itemStack) {
        if (itemStack.getItem() instanceof ItemMonsterPlacer) {
            Entity value = EntityList.createEntityByID(itemStack.getItemDamage(), Minecraft.getMinecraft().theWorld);
            if (value != null) {
                TotalDebug.PROXY.getDecompilationManager().openGui(value.getClass());
                return true;
            }
        }
        return false;
    }

    public boolean rayTraceEyes() {
        MovingObjectPosition rayTraceResult = Minecraft.getMinecraft().objectMouseOver;
        WorldClient world = Minecraft.getMinecraft().theWorld;

        switch (rayTraceResult.typeOfHit) {
            case BLOCK:
                BlockPos blockPos = new BlockPos(rayTraceResult.blockX, rayTraceResult.blockY, rayTraceResult.blockZ);
                if (!world.isAirBlock(blockPos.getX(), blockPos.getY(), blockPos.getZ())) {
                    if (world.getTileEntity(blockPos.getX(), blockPos.getY(), blockPos.getZ()) == null) {
                        handle(HitType.BLOCK_ENTITY, blockPos, 0, 0);
                    } else {
                        handle(HitType.TILE_ENTITY, blockPos, 0, 0);
                    }
                }
                return true;
            case ENTITY:
                handle(HitType.LIVING_ENTITY, null, rayTraceResult.entityHit.getEntityId(), 0);
                return true;
        }
        return false;
    }

    public void handle(HitType typeOfHit, BlockPos pos, int entityOrItemId, int meta) {
        if (System.currentTimeMillis() - lastRequested < 500)
            return;
        lastRequested = System.currentTimeMillis();

        final World world = Minecraft.getMinecraft().theWorld;

        switch (typeOfHit) {
            case BLOCK_ENTITY:
                TotalDebug.PROXY.getDecompilationManager().openGui(world.getBlock(pos.getX(), pos.getY(), pos.getZ()).getClass());
                break;
            case TILE_ENTITY:
                TileEntity tileEntity = world.getTileEntity(pos.getX(), pos.getY(), pos.getZ());
                if (tileEntity instanceof IGregTechTileEntity) {
                    IMetaTileEntity metaTileEntity = ((IGregTechTileEntity) tileEntity).getMetaTileEntity();
                    if (metaTileEntity != null) {
                        TotalDebug.PROXY.getDecompilationManager().openGui(metaTileEntity.getClass());
                    }
                } else if (tileEntity != null) {
                    TotalDebug.PROXY.getDecompilationManager().openGui(tileEntity.getClass());
                } else {
                    TotalDebug.LOGGER.error("TileEntity is null");
                }
                break;
            case LIVING_ENTITY:
                Entity entity = world.getEntityByID(entityOrItemId);
                if (entity != null) {
                    TotalDebug.PROXY.getDecompilationManager().openGui(entity.getClass());
                } else {
                    TotalDebug.LOGGER.error("Entity is null");
                }
                break;
            case ITEM:
                Item item = (Item) Item.itemRegistry.getObjectById(entityOrItemId);
                if (item != null) {
                    if (item instanceof ItemBlock) {
                        Block block = ((ItemBlock) item).field_150939_a;
                        TileEntity tile = block.createTileEntity(world, meta);
                        if (tile != null) {
                            if (tile instanceof IGregTechTileEntity) {
                                IMetaTileEntity metatileentity = GregTech_API.METATILEENTITIES[meta];
                                if (metatileentity != null) {
                                    TotalDebug.PROXY.getDecompilationManager().openGui(metatileentity.getClass());
                                    return;
                                }
                            }
                            TotalDebug.PROXY.getDecompilationManager().openGui(tile.getClass());
                            return;
                        }
                        TotalDebug.PROXY.getDecompilationManager().openGui(block.getClass());
                    } else {
                        TotalDebug.PROXY.getDecompilationManager().openGui(item.getClass());
                    }
                } else {
                    TotalDebug.LOGGER.error("Item is null");
                }
        }
    }

    public enum HitType {
        BLOCK_ENTITY,
        TILE_ENTITY,
        LIVING_ENTITY,
        ITEM
    }
}
