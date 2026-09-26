package com.github.minecraft_ta.totaldebug.client.input;

import com.github.minecraft_ta.totaldebug.client.inspection.ItemIcons;
import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectRef;
import com.github.minecraft_ta.totaldebug.script.SubjectIdentities;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.Optional;
import java.util.function.Function;

final class CodeTargetResolver {
    private CodeTargetResolver() {
    }

    static Optional<WorldSubject> resolveWorldTarget(Minecraft minecraft) {
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

    private static WorldSubject block(ClientLevel level, String dimension, BlockPos position) {
        BlockState state = level.getBlockState(position);
        return new WorldSubject(
                new SubjectRef.Block(dimension, position.getX(), position.getY(), position.getZ()),
                SubjectIdentities.block(state, level.getBlockEntity(position)),
                ItemIcons.of(new ItemStack(state.getBlock().asItem()))
        );
    }

    private static WorldSubject entity(Entity entity) {
        return new WorldSubject(
                new SubjectRef.Entity(entity.getUUID()),
                SubjectIdentities.entity(entity),
                spawnEgg(entity).flatMap(ItemIcons::of)
        );
    }

    private static Optional<ItemStack> spawnEgg(Entity entity) {
        SpawnEggItem egg = SpawnEggItem.byId(entity.getType());
        return egg == null ? Optional.empty() : Optional.of(new ItemStack(egg));
    }

    static Optional<Class<?>> resolveItemTarget(Minecraft minecraft, ItemStack itemStack) {
        var level = minecraft.level;
        if (level == null || itemStack.isEmpty()) {
            return Optional.empty();
        }

        if (itemStack.getItem() instanceof SpawnEggItem spawnEgg) {
            Entity entity = spawnEgg.getType(itemStack).create(level);
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
