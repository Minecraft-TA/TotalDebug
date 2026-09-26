package com.github.minecraft_ta.totaldebug.inspection;

import com.github.minecraft_ta.totaldebug.script.ScriptFacts;
import com.github.minecraft_ta.totaldebug.script.ScriptTarget;
import net.minecraft.nbt.CompoundTag;

/**
 * Built-in reader for the data a block entity, entity or stack would save. Saving an unopened loot container writes its
 * loot table reference without generating the loot.
 */
public final class NbtReader {
    private NbtReader() {
    }

    public static void read(ScriptTarget target, ScriptFacts facts) {
        switch (target) {
            case ScriptTarget.PlacedBlock block -> {
                if (block.blockEntity() != null) {
                    facts.section("NBT").nbt("Block entity",
                            block.blockEntity().saveWithoutMetadata(block.level().registryAccess()));
                }
            }
            case ScriptTarget.LiveEntity entity -> facts.section("NBT")
                    .nbt("Entity", entity.entity().saveWithoutId(new CompoundTag()));
            case ScriptTarget.HeldStack held -> facts.section("NBT")
                    .nbt("Stack", held.stack().save(held.level().registryAccess()));
        }
    }
}
