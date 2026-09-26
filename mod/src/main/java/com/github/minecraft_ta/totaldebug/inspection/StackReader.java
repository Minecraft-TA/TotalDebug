package com.github.minecraft_ta.totaldebug.inspection;

import com.github.minecraft_ta.totaldebug.script.ScriptFacts;
import com.github.minecraft_ta.totaldebug.script.ScriptTarget;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.Optional;

/**
 * Built-in reader for a selected stack: its count and durability, and each component it carries beyond its item's
 * defaults, such as enchantments or a custom name.
 */
public final class StackReader {
    private StackReader() {
    }

    public static void read(ScriptTarget target, ScriptFacts facts) {
        if (!(target instanceof ScriptTarget.SelectedStack selected)) return;
        ItemStack stack = selected.stack();
        ScriptFacts.Section section = facts.section("Stack");
        section.text("Count", stack.getCount() + " of " + stack.getMaxStackSize());
        if (stack.isDamageableItem()) {
            section.bar("Durability", stack.getMaxDamage() - stack.getDamageValue(), stack.getMaxDamage(), "");
        }
        DataComponentPatch changes = stack.getComponentsPatch();
        if (changes.isEmpty()) return;
        DynamicOps<Tag> ops = selected.level().registryAccess().createSerializationContext(NbtOps.INSTANCE);
        ScriptFacts.Section components = facts.section("Components");
        for (Map.Entry<DataComponentType<?>, Optional<?>> change : changes.entrySet()) {
            DataComponentType<?> type = change.getKey();
            if (type == DataComponents.DAMAGE) continue;
            String id = String.valueOf(BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type));
            components.text(id, change.getValue().map(value -> value(type, value, ops)).orElse("Removed"));
        }
    }

    /**
     * A component's value: text as it reads, such as a custom name; other values as SNBT, or as text when the
     * component is not saved.
     */
    @SuppressWarnings("unchecked")
    private static <T> String value(DataComponentType<T> type, Object value, DynamicOps<Tag> ops) {
        if (value instanceof Component text) return text.getString();
        Codec<T> codec = type.codec();
        if (codec == null) return String.valueOf(value);
        return codec.encodeStart(ops, (T) value).result().map(Tag::toString).orElse(String.valueOf(value));
    }
}
