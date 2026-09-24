package com.github.minecraft_ta.totaldebug.inspection;

import com.github.minecraft_ta.totaldebug.script.ScriptFacts;
import com.github.minecraft_ta.totaldebug.script.ScriptTarget;
import net.minecraft.core.Direction;

/**
 * The built-in readers an inspection runs. Each reads independently: a failing reader reports a problem in its
 * section and the others still run.
 */
public final class InspectionReaders {
    private InspectionReaders() {
    }

    /** Reads {@code target} as seen from {@code side}, or through its unsided handlers when {@code side} is null. */
    public static void read(ScriptTarget target, Direction side, ScriptFacts facts) {
        StorageReader.read(target, side, facts);
        facts.guarded("Capabilities", () -> CapabilityReader.read(target, side, facts));
        facts.guarded("NBT", () -> NbtReader.read(target, facts));
    }
}
