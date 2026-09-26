package com.github.minecraft_ta.totaldebug.script;

import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectRef;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import java.util.Objects;

/**
 * The live object a script run was started for, found in the running side's world. Components are ordered so the
 * most specific object comes first in a captured result.
 */
public sealed interface ScriptTarget {
    SubjectRef.Occurrence subject();

    Level level();

    /** A block at a loaded position. {@code blockEntity} is null when the block has none. */
    record PlacedBlock(BlockEntity blockEntity, BlockState state, BlockPos pos, SubjectRef.Block subject, Level level)
            implements ScriptTarget {
        public PlacedBlock {
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(pos, "pos");
            Objects.requireNonNull(subject, "subject");
            Objects.requireNonNull(level, "level");
        }
    }

    /** A stack selected in a screen, as it was when selected; the client keeps it. */
    record SelectedStack(ItemStack stack, SubjectRef.Stack subject, Level level) implements ScriptTarget {
        public SelectedStack {
            Objects.requireNonNull(stack, "stack");
            Objects.requireNonNull(subject, "subject");
            Objects.requireNonNull(level, "level");
        }
    }

    /** A loaded entity. */
    record LiveEntity(Entity entity, SubjectRef.Entity subject, Level level) implements ScriptTarget {
        public LiveEntity {
            Objects.requireNonNull(entity, "entity");
            Objects.requireNonNull(subject, "subject");
            Objects.requireNonNull(level, "level");
        }
    }
}
