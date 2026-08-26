package net.minecraft.world.level.block;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

public interface BonemealableBlock {
    default void performBonemeal(
            ServerLevel p_220836_,
            RandomSource p_220837_,
            BlockPos p_220838_,
            BlockState p_220839_
    ) {
    }
}
