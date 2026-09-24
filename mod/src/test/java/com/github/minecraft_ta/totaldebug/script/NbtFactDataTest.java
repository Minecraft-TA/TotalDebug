package com.github.minecraft_ta.totaldebug.script;

import com.github.minecraft_ta.totaldebug.protocol.execution.FactData;
import com.github.minecraft_ta.totaldebug.protocol.nbt.NbtData;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NbtFactDataTest {
    /** Tags whose printing depends on quoting, escaping, key order and number formatting. */
    static CompoundTag corpus() {
        CompoundTag root = new CompoundTag();
        root.putString("plain", "minecraft:coal");
        root.putString("double quote", "say \"hi\"");
        root.putString("single quote", "it's");
        root.putString("both", "it's \"both\"");
        root.putString("first double", "\"a\" and 'b'");
        root.putString("backslash", "C:\\path\\to");
        root.putString("unicode", "Grüße ✓ 🙂");
        root.putString("", "empty key");
        root.putString("key:with/odd.chars+-_", "simple");
        root.putString("{json}", "{\"text\":\"Excalibur\",\"italic\":false}");
        root.putString("control", "line\nbreak\ttab");
        root.putByte("byte", (byte) -3);
        root.putShort("short", (short) 120);
        root.putInt("int", Integer.MIN_VALUE);
        root.putLong("long", Long.MAX_VALUE);
        root.putFloat("float", 1.0E10f);
        root.putFloat("float small", 1.5E-7f);
        root.putDouble("double", -0.0);
        root.put("nan", DoubleTag.valueOf(Double.NaN));
        root.put("infinity", FloatTag.valueOf(Float.POSITIVE_INFINITY));
        root.put("bytes", new ByteArrayTag(new byte[]{-128, 0, 127}));
        root.put("ints", new IntArrayTag(new int[]{1, -2, 3}));
        root.put("longs", new LongArrayTag(new long[]{4L, -5L}));
        root.put("empty bytes", new ByteArrayTag(new byte[0]));
        ListTag nested = new ListTag();
        ListTag inner = new ListTag();
        inner.add(IntTag.valueOf(1));
        inner.add(IntTag.valueOf(2));
        nested.add(inner);
        nested.add(new ListTag());
        root.put("nested lists", nested);
        ListTag items = new ListTag();
        for (int slot = 0; slot < 3; slot++) {
            CompoundTag item = new CompoundTag();
            item.putByte("Slot", (byte) slot);
            item.putString("id", "minecraft:iron_ingot");
            item.putInt("count", 16 + slot);
            CompoundTag components = new CompoundTag();
            components.putString("minecraft:custom_name", "{\"text\":\"Stack " + slot + "\"}");
            item.put("components", components);
            items.add(item);
        }
        root.put("Items", items);
        root.put("empty compound", new CompoundTag());
        root.put("empty list", new ListTag());
        return root;
    }

    @Test
    void companionPrintsExactlyWhatMinecraftPrints() {
        CompoundTag root = corpus();

        FactData data = NbtFactData.encode(root, FactData.MAX_BYTES);

        assertTrue(data.complete());
        assertEquals(root.toString(), NbtData.snbt(data.tag()));
        for (String key : root.getAllKeys()) {
            Tag child = root.get(key);
            NbtData.Tag decoded = ((NbtData.CompoundTag) data.tag()).entries().get(key);
            assertEquals(child.toString(), NbtData.snbt(decoded), key);
        }
    }

    @Test
    void keysArePrintedAsMinecraftPrintsThem() {
        for (String key : List.of("plain", "", "with space", "colon:key", "dots.and+plus-_", "quote\"key", "ü")) {
            CompoundTag tag = new CompoundTag();
            tag.putInt(key, 1);
            assertEquals(tag.toString(), "{" + NbtData.key(key) + ":1}", key);
        }
        for (String text : List.of("a", "\"", "'", "\\", "'\"", "\"'", "")) {
            assertEquals(StringTag.valueOf(text).toString(), NbtData.quote(text), text);
        }
    }

    @Test
    void theSizeEstimateMatchesWhatIsWritten() {
        CompoundTag root = corpus();

        FactData data = NbtFactData.encode(root, FactData.MAX_BYTES);

        assertEquals(NbtFactData.serializedSize(root) + 1, data.size());
        assertEquals(data.bytes().length, data.size());
    }

    @Test
    void aTagOverTheBudgetKeepsItsFirstEntriesAndNamesWhatWasLeftOut() {
        CompoundTag root = new CompoundTag();
        ListTag values = new ListTag();
        for (int index = 0; index < 5_000; index++) {
            values.add(IntTag.valueOf(index));
        }
        root.put("Values", values);
        root.putString("Zeta", "after the large list");

        FactData data = NbtFactData.encode(root, 2_000);

        assertFalse(data.complete());
        assertTrue(data.size() <= 2_000, "encoded " + data.size());
        NbtData.CompoundTag decoded = (NbtData.CompoundTag) data.tag();
        NbtData.ListTag kept = (NbtData.ListTag) decoded.entries().get("Values");
        assertTrue(kept.items().size() > 100, "kept " + kept.items().size());
        assertEquals(new FactData.Omission("Values", 5_000 - kept.items().size()), data.omissions().getFirst());
        assertEquals(new FactData.Omission("", 1), data.omissions().get(1));
        assertFalse(decoded.entries().containsKey("Zeta"));
    }

    @Test
    void omissionsUseTheDataCommandPathSyntax() {
        CompoundTag root = new CompoundTag();
        ListTag items = new ListTag();
        CompoundTag item = new CompoundTag();
        item.putString("odd key", "x".repeat(500));
        items.add(item);
        root.put("Items", items);

        FactData data = NbtFactData.encode(root, 100);

        assertEquals("Items[0]", data.omissions().getFirst().path());
    }
}
