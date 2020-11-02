package com.github.minecraft_ta.totaldebug.handler;


import com.github.minecraft_ta.totaldebug.HitType;
import com.github.minecraft_ta.totaldebug.KeyBindings;
import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.network.DecompilationRequestMessage;
import com.github.minecraft_ta.totaldebug.network.LoadedRequestMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.inventory.SlotShulkerBox;
import net.minecraft.item.Item;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;

public class KeyInputHandler {

    @SubscribeEvent
    public void onKeyPress(InputEvent.KeyInputEvent event) {
        if (KeyBindings.CODE_GUI.isKeyDown()) {
            GuiContainer currentScreen = (GuiContainer) Minecraft.getMinecraft().currentScreen;
            if (currentScreen instanceof GuiInventory) {
                Slot slot = currentScreen.getSlotUnderMouse();
                if (slot != null && slot.getHasStack()) {
                    Item item = currentScreen.getSlotUnderMouse().getStack().getItem();
                    TotalDebug.INSTANCE.network.sendToServer(new DecompilationRequestMessage(HitType.ITEM, Item.REGISTRY.getIDForObject(item)));
                }
            } else {
                rayTraceEyes();
            }
        } else if (KeyBindings.LOADED_GUI.isKeyDown()) {
            TotalDebug.INSTANCE.network.sendToServer(new LoadedRequestMessage());
        }
    }

    public void rayTraceEyes() {
        RayTraceResult rayTraceResult = Minecraft.getMinecraft().objectMouseOver;
        WorldClient world = Minecraft.getMinecraft().world;

        switch (rayTraceResult.typeOfHit) {
            case BLOCK:
                BlockPos blockPos = rayTraceResult.getBlockPos();
                if (!world.isAirBlock(blockPos)) {
                    if (world.getTileEntity(blockPos) == null) {
                        TotalDebug.INSTANCE.network.sendToServer(new DecompilationRequestMessage(HitType.BLOCK_ENTITY, blockPos));
                    } else {
                        TotalDebug.INSTANCE.network.sendToServer(new DecompilationRequestMessage(HitType.TILE_ENTITY, blockPos));
                    }
                }
                break;
            case ENTITY:
                TotalDebug.INSTANCE.network.sendToServer(new DecompilationRequestMessage(HitType.LIVING_ENTITY, rayTraceResult.entityHit.getEntityId()));
                break;
        }
    }
}
