package com.github.minecraft_ta.totaldebug.client.input;

import com.github.minecraft_ta.totaldebug.client.inspection.ItemIcons;
import com.github.minecraft_ta.totaldebug.client.inspection.KeptStacks;
import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectRef;
import com.github.minecraft_ta.totaldebug.script.SubjectIdentities;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
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

    /** A stack shown in a screen, kept as it is now so its page shows this very stack. */
    static Selection stack(KeptStacks kept, ItemStack stack) {
        return new Selection(new SubjectRef.Stack(kept.keep(stack)), SubjectIdentities.stack(stack), ItemIcons.of(stack));
    }
}
