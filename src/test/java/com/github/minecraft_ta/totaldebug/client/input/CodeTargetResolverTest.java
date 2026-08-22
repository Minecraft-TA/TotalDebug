package com.github.minecraft_ta.totaldebug.client.input;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeTargetResolverTest {
    @Test
    void worldMissDoesNotResolveTheAirBlockAtItsMissPosition() {
        BlockHitResult miss = BlockHitResult.miss(Vec3.ZERO, Direction.NORTH, BlockPos.ZERO);
        AtomicBoolean blockLookupCalled = new AtomicBoolean();

        Optional<Class<?>> result = CodeTargetResolver.resolveWorldTarget(miss, position -> {
            blockLookupCalled.set(true);
            return Optional.of(String.class);
        });

        assertTrue(result.isEmpty());
        assertFalse(blockLookupCalled.get());
    }
}
