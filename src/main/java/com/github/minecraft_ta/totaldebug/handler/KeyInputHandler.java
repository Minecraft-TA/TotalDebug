package com.github.minecraft_ta.totaldebug.handler;

import com.github.minecraft_ta.totaldebug.KeyBindings;
import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.gui.codeviewer.CodeViewScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;
import net.minecraft.inventory.Slot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.world.World;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.fml.client.FMLClientHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import org.lwjgl.input.Keyboard;

public class KeyInputHandler {

    private long lastRequested;

    @SubscribeEvent
    public void onKeyPress(InputEvent.KeyInputEvent event) {
        if (KeyBindings.CODE_GUI.isKeyDown()) {
            rayTraceEyes();
        }
    }

    @SubscribeEvent
    public void onGuiKeyPress(GuiScreenEvent.KeyboardInputEvent.Pre event) {
        if (!Keyboard.isKeyDown(KeyBindings.CODE_GUI.getKeyCode()))
            return;

        GuiScreen currentScreen = Minecraft.getMinecraft().currentScreen;
        if (!(currentScreen instanceof GuiContainer))
            return;

        GuiContainer guiContainer = (GuiContainer) currentScreen;
        Slot slot = guiContainer.getSlotUnderMouse();
        if (slot != null && slot.getHasStack()) {
            Item item = guiContainer.getSlotUnderMouse().getStack().getItem();
            handle(HitType.ITEM, null, Item.REGISTRY.getIDForObject(item));
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
                        handle(HitType.BLOCK_ENTITY, blockPos, 0);
                    } else {
                        handle(HitType.TILE_ENTITY, blockPos, 0);
                    }
                }
                break;
            case ENTITY:
                handle(HitType.LIVING_ENTITY, null, rayTraceResult.entityHit.getEntityId());
                break;
        }
    }

    public void handle(HitType typeOfHit, BlockPos pos, int entityOrItemId) {
        if (System.currentTimeMillis() - lastRequested < 500)
            return;
        lastRequested = System.currentTimeMillis();

        World world = Minecraft.getMinecraft().world;

        switch (typeOfHit) {
            case BLOCK_ENTITY:
                openGui(world.getBlockState(pos).getBlock().getClass());
                break;
            case TILE_ENTITY:
                TileEntity tileEntity = world.getTileEntity(pos);
                if (tileEntity != null) {
                    openGui(tileEntity.getClass());
                } else {
                    TotalDebug.LOGGER.error("TileEntity is null");
                }
                break;
            case LIVING_ENTITY:
                Entity entity = world.getEntityByID(entityOrItemId);
                if (entity != null) {
                    openGui(entity.getClass());
                } else {
                    TotalDebug.LOGGER.error("Entity is null");
                }
                break;
            case ITEM:
                Item item = Item.REGISTRY.getObjectById(entityOrItemId);
                if (item != null) {
                    if (item instanceof ItemBlock) {
                        openGui(((ItemBlock) item).getBlock().getClass());
                    } else {
                        openGui(item.getClass());
                    }
                } else {
                    TotalDebug.LOGGER.error("Item is null");
                }
        }
    }

    public void openGui(Class<?> clazz) {
        TotalDebug.PROXY.getDecompilationManager().getDecompiledFileContent(clazz).thenAccept(s -> {
            Minecraft.getMinecraft().addScheduledTask(() -> {
                CodeViewScreen screen = new CodeViewScreen();
                FMLClientHandler.instance().showGuiScreen(screen);
                screen.setJavaCode(s);
            });
        });
    }

    public enum HitType {
        BLOCK_ENTITY,
        TILE_ENTITY,
        LIVING_ENTITY,
        ITEM
    }
}
