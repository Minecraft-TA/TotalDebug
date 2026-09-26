package com.github.minecraft_ta.totaldebug.inspection;

import com.github.minecraft_ta.totaldebug.script.ScriptFacts;
import com.github.minecraft_ta.totaldebug.script.ScriptTarget;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.item.ItemStack;

/**
 * Built-in reader for a stack a player holds: its count and damage, and the components it carries beyond its item's
 * defaults, such as enchantments or a custom name.
 */
public final class StackReader {
    private StackReader() {
    }

    public static void read(ScriptTarget target, ScriptFacts facts) {
        if (!(target instanceof ScriptTarget.HeldStack held)) return;
        ItemStack stack = held.stack();
        ScriptFacts.Section section = facts.section("Stack");
        section.text("Count", stack.getCount() + " of " + stack.getMaxStackSize());
        if (stack.isDamageableItem()) {
            section.bar("Durability", stack.getMaxDamage() - stack.getDamageValue(), stack.getMaxDamage(), "");
        }
        DataComponentPatch changes = stack.getComponentsPatch();
        if (!changes.isEmpty()) {
            var ops = held.level().registryAccess().createSerializationContext(NbtOps.INSTANCE);
            section.nbt("Components", DataComponentPatch.CODEC.encodeStart(ops, changes).getOrThrow());
        }
    }
}
