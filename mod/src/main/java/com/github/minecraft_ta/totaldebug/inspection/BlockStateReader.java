package com.github.minecraft_ta.totaldebug.inspection;

import com.github.minecraft_ta.totaldebug.script.ScriptFacts;
import com.github.minecraft_ta.totaldebug.script.ScriptTarget;
import net.minecraft.world.level.block.state.BlockState;

/** Built-in reader for the properties of a placed block's state, such as its facing. */
public final class BlockStateReader {
    private BlockStateReader() {
    }

    public static void read(ScriptTarget target, ScriptFacts facts) {
        if (!(target instanceof ScriptTarget.PlacedBlock block)) return;
        BlockState state = block.state();
        if (state.getValues().isEmpty()) return;
        ScriptFacts.Section properties = facts.section("Block state");
        state.getValues().forEach((property, value) -> properties.text(property.getName(), value));
    }
}
