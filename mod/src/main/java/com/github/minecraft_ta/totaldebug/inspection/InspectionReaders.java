package com.github.minecraft_ta.totaldebug.inspection;

import com.github.minecraft_ta.totaldebug.script.ScriptFacts;
import com.github.minecraft_ta.totaldebug.script.ScriptTarget;

/**
 * The built-in readers an inspection runs. Each reads independently: a failing reader reports a problem in its
 * section and the others still run.
 */
public final class InspectionReaders {
    private InspectionReaders() {
    }

    public static void read(ScriptTarget target, ScriptFacts facts) {
        facts.guarded("Stack", () -> StackReader.read(target, facts));
        facts.guarded("Entity state", () -> EntityReader.read(target, facts));
        facts.guarded("Block state", () -> BlockStateReader.read(target, facts));
        StorageReader.read(target, facts);
        SideReader.read(target, facts);
        facts.guarded("Capabilities", () -> CapabilityReader.read(target, facts));
        facts.guarded("NBT", () -> NbtReader.read(target, facts));
    }
}
