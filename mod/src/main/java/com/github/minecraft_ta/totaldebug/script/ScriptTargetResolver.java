package com.github.minecraft_ta.totaldebug.script;

import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectIdentity;
import com.github.minecraft_ta.totaldebug.protocol.inspection.SubjectRef;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * Finds a subject in one side's world. Called on the thread running the script. Resolution never loads chunks and
 * reports an unavailable subject with an exception naming the reason.
 */
@FunctionalInterface
public interface ScriptTargetResolver {
    ScriptTarget resolve(SubjectRef.Occurrence subject);

    /** Describes what a resolved target currently is; used to report it and to reject a changed subject. */
    default SubjectIdentity identify(ScriptTarget target) {
        return SubjectIdentities.of(target);
    }

    static ScriptTarget.PlacedBlock block(Level level, SubjectRef.Block subject) {
        BlockPos pos = new BlockPos(subject.x(), subject.y(), subject.z());
        if (!level.isLoaded(pos)) {
            throw new IllegalStateException("The chunk containing " + subject.x() + " " + subject.y() + " "
                    + subject.z() + " in " + subject.dimension() + " is not loaded");
        }
        return new ScriptTarget.PlacedBlock(level.getBlockEntity(pos), level.getBlockState(pos), pos, subject, level);
    }
}
