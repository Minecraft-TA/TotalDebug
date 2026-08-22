package com.github.minecraft_ta.totaldebug.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.Optional;

final class CodeTargetResolver {
    private CodeTargetResolver() {
    }

    static Optional<Class<?>> resolveWorldTarget(Minecraft minecraft) {
        if (minecraft.level == null || minecraft.hitResult == null) {
            return Optional.empty();
        }

        HitResult hitResult = minecraft.hitResult;
        if (hitResult instanceof BlockHitResult blockHit) {
            BlockPos position = blockHit.getBlockPos();
            BlockEntity blockEntity = minecraft.level.getBlockEntity(position);
            if (blockEntity != null) {
                return Optional.of(blockEntity.getClass());
            }
            return Optional.of(minecraft.level.getBlockState(position).getBlock().getClass());
        }
        if (hitResult instanceof EntityHitResult entityHit) {
            return Optional.of(entityHit.getEntity().getClass());
        }
        return Optional.empty();
    }

    static Optional<Class<?>> resolveItemTarget(Minecraft minecraft, ItemStack itemStack) {
        if (minecraft.level == null || itemStack.isEmpty()) {
            return Optional.empty();
        }

        if (itemStack.getItem() instanceof SpawnEggItem spawnEgg) {
            Entity entity = spawnEgg.getType(itemStack).create(minecraft.level);
            return entity == null ? Optional.empty() : Optional.of(entity.getClass());
        }

        if (itemStack.getItem() instanceof BlockItem blockItem) {
            var block = blockItem.getBlock();
            if (block instanceof EntityBlock entityBlock) {
                BlockEntity blockEntity = entityBlock.newBlockEntity(BlockPos.ZERO, block.defaultBlockState());
                if (blockEntity != null) {
                    return Optional.of(blockEntity.getClass());
                }
            }
            return Optional.of(block.getClass());
        }

        return Optional.of(itemStack.getItem().getClass());
    }
}
