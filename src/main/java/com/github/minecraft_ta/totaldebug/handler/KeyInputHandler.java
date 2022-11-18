package com.github.minecraft_ta.totaldebug.handler;

import com.github.minecraft_ta.totaldebug.KeyBindings;
import com.github.minecraft_ta.totaldebug.TotalDebug;
import com.github.minecraft_ta.totaldebug.companionApp.CompanionApp;
import com.github.minecraft_ta.totaldebug.companionApp.messages.FocusWindowMessage;
import com.github.minecraft_ta.totaldebug.util.BlockPos;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.world.World;
import net.minecraftforge.client.event.GuiScreenEvent;

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
        });
    }

    @SubscribeEvent
    public void onGuiKeyPress(GuiScreenEvent/*.KeyboardInputEvent.Pre*/ event) {
        /*if (!Keyboard.isKeyDown(KeyBindings.CODE_GUI.getKeyCode())) TODO Replace with Nei integration
            return;

        GuiScreen currentScreen = event.getGui();
        if (currentScreen instanceof GuiContainer) {
            if (TotalDebugJEIPlugin.INSTANCE != null) {
                IJeiRuntime runtime = TotalDebugJEIPlugin.INSTANCE.getRuntime();

                Object ingredientUnderMouse = runtime.getIngredientListOverlay().getIngredientUnderMouse();
                if (ingredientUnderMouse == null)
                    ingredientUnderMouse = runtime.getBookmarkOverlay().getIngredientUnderMouse();

                if (ingredientUnderMouse instanceof ItemStack) {
                    ItemStack itemStack = ((ItemStack) ingredientUnderMouse);
                    Item item = itemStack.getItem();

                    if (!checkForSpawnEggAndOpenGui(itemStack)) {
                        handle(HitType.ITEM, null, Item.getIdFromItem(item), itemStack.getMetadata());
                    }
                    return;
                } else if (ingredientUnderMouse instanceof EnchantmentData) {
                    TotalDebug.PROXY.getDecompilationManager().openGui(((EnchantmentData) ingredientUnderMouse).enchantment.getClass());
                }
            }

            GuiContainer guiContainer = (GuiContainer) currentScreen;
            Slot slot = guiContainer.getSlotUnderMouse();
            if (slot != null && slot.getHasStack()) {
                ItemStack itemStack = guiContainer.getSlotUnderMouse().getStack();
                if (!checkForSpawnEggAndOpenGui(itemStack)) {
                    handle(HitType.ITEM, null, Item.REGISTRY.getIDForObject(itemStack.getItem()), itemStack.getMetadata());
                }
            }
        }*/
    }

    private boolean checkForSpawnEggAndOpenGui(ItemStack itemStack) {
        /*if (itemStack.getItem() instanceof ItemMonsterPlacer) { TODO Readd later
            final EntityEntry value = ForgeRegistries.ENTITIES.getValue(ItemMonsterPlacer.getNamedIdFrom(itemStack));
            if (value != null) {
                TotalDebug.PROXY.getDecompilationManager().openGui(value.getEntityClass());
                return true;
            }
        }*/
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
                if (tileEntity != null) {
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
                Item item = (Item) Item.itemRegistry.getObjectById(entityOrItemId); //TODO: Save I guess?
                if (item != null) {
                    if (item instanceof ItemBlock) {
                        Block block = ((ItemBlock) item).field_150939_a;
                        TileEntity tile = block.createTileEntity(world, block.getDamageValue(world, pos.getX(), pos.getY(), pos.getZ()));
                        if (tile != null) {
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
