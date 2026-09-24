package com.github.minecraft_ta.totaldebug.client.input;

import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectRef;
import com.github.minecraft_ta.totaldebug.protocol.message.InspectSubjectPayload.ClassLink;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
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
import net.neoforged.fml.ModList;

import java.util.ArrayList;
import java.util.List;
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
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        List<ClassLink> classes = new ArrayList<>();
        classes.add(new ClassLink("Block", state.getBlock().getClass().getName()));
        BlockEntity blockEntity = level.getBlockEntity(position);
        if (blockEntity != null) {
            classes.add(new ClassLink("Block entity", blockEntity.getClass().getName()));
        }
        return new WorldSubject(
                new SubjectRef.Block(dimension, position.getX(), position.getY(), position.getZ()),
                state.getBlock().getName().getString(),
                id.toString(),
                modName(id.getNamespace()),
                classes
        );
    }

    private static WorldSubject entity(Entity entity) {
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return new WorldSubject(
                new SubjectRef.Entity(entity.getUUID()),
                entity.getName().getString(),
                id.toString(),
                modName(id.getNamespace()),
                List.of(new ClassLink("Entity", entity.getClass().getName()))
        );
    }

    private static String modName(String namespace) {
        return ModList.get().getModContainerById(namespace)
                .map(container -> container.getModInfo().getDisplayName())
                .orElse(namespace);
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
