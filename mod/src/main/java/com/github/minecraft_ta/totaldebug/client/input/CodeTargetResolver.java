package com.github.minecraft_ta.totaldebug.client.input;

import com.github.minecraft_ta.totaldebug.client.inspection.ItemIcons;
import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectRef;
import com.github.minecraft_ta.totaldebug.script.SubjectIdentities;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.Optional;
import java.util.function.Function;

final class CodeTargetResolver {
    private CodeTargetResolver() {
    }

    static Optional<Selection> resolveWorldTarget(Minecraft minecraft) {
        ClientLevel level = minecraft.level;
        if (level == null) {
            return Optional.empty();
        }

        String dimension = level.dimension().location().toString();
        return resolveWorldTarget(
                minecraft.hitResult,
                position -> Optional.of(block(level, dimension, position)),
                entity -> Optional.of(entity(entity))
        );
    }

    static <T> Optional<T> resolveWorldTarget(
            HitResult hitResult,
            Function<BlockPos, Optional<T>> blockTarget,
            Function<Entity, Optional<T>> entityTarget
    ) {
        if (hitResult == null || hitResult.getType() == HitResult.Type.MISS) {
            return Optional.empty();
        }
        if (hitResult instanceof BlockHitResult blockHit) {
            return blockTarget.apply(blockHit.getBlockPos());
        }
        if (hitResult instanceof EntityHitResult entityHit) {
            return entityTarget.apply(entityHit.getEntity());
        }
        return Optional.empty();
    }

    private static Selection block(ClientLevel level, String dimension, BlockPos position) {
        BlockState state = level.getBlockState(position);
        return new Selection(
                new SubjectRef.Block(dimension, position.getX(), position.getY(), position.getZ()),
                SubjectIdentities.block(state, level.getBlockEntity(position)),
                ItemIcons.of(new ItemStack(state.getBlock().asItem()))
        );
    }

    private static Selection entity(Entity entity) {
        return new Selection(
                new SubjectRef.Entity(entity.getUUID()),
                SubjectIdentities.entity(entity),
                spawnEgg(entity).flatMap(ItemIcons::of)
        );
    }

    private static Optional<ItemStack> spawnEgg(Entity entity) {
        SpawnEggItem egg = SpawnEggItem.byId(entity.getType());
        return egg == null ? Optional.empty() : Optional.of(new ItemStack(egg));
    }

    /**
     * The stack in a screen's slot. A stack in the player's inventory is named by its inventory slot, whatever screen
     * shows it; one in an open container by that container's slot. The creative inventory's item lists are in no real
     * slot, so their stacks open their item's definition.
     */
    static Optional<Selection> resolveSlotTarget(Minecraft minecraft, Screen screen, Slot slot) {
        LocalPlayer player = minecraft.player;
        if (player == null || !(screen instanceof AbstractContainerScreen<?> containerScreen) || !slot.hasItem()) {
            return Optional.empty();
        }
        ItemStack stack = slot.getItem();
        Inventory inventory = player.getInventory();
        for (int index = 0; index < inventory.getContainerSize(); index++) {
            if (inventory.getItem(index) == stack) {
                return Optional.of(stack(new SubjectRef.Stack(player.getUUID(), SubjectRef.Stack.INVENTORY, index), stack));
            }
        }
        if (screen instanceof CreativeModeInventoryScreen) {
            return Optional.of(definition(stack));
        }
        return Optional.of(stack(new SubjectRef.Stack(player.getUUID(), containerScreen.getMenu().containerId, slot.index), stack));
    }

    /** An item shown in no slot, such as in a recipe viewer: its definition. */
    static Selection definition(ItemStack stack) {
        SubjectRef.Definition definition = new SubjectRef.Definition("minecraft:item",
                BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        return new Selection(definition, SubjectIdentities.stack(stack), ItemIcons.of(stack));
    }

    private static Selection stack(SubjectRef.Stack subject, ItemStack stack) {
        return new Selection(subject, SubjectIdentities.stack(stack), ItemIcons.of(stack));
    }
}
