package com.github.minecraft_ta.totaldebug.protocol.nbt;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NbtDataTest {
    @Test
    void readsACompoundAndPrintsItsKeysInSortedOrder() throws IOException {
        byte[] bytes = tag(output -> {
            output.writeByte(10);
            output.writeByte(8);
            output.writeUTF("id");
            output.writeUTF("minecraft:furnace");
            output.writeByte(2);
            output.writeUTF("BurnTime");
            output.writeShort(120);
            output.writeByte(9);
            output.writeUTF("Items");
            output.writeByte(10);
            output.writeInt(1);
            output.writeByte(1);
            output.writeUTF("Slot");
            output.writeByte(0);
            output.writeByte(0);
            output.writeByte(12);
            output.writeUTF("longs");
            output.writeInt(2);
            output.writeLong(1);
            output.writeLong(-2);
            output.writeByte(0);
        });

        NbtData.Tag tag = NbtData.read(bytes);

        assertEquals("{BurnTime:120s,Items:[{Slot:0b}],id:\"minecraft:furnace\",longs:[L;1L,-2L]}", NbtData.snbt(tag));
        assertEquals("""
                {
                  BurnTime: 120s,
                  Items: [
                    {
                      Slot: 0b
                    }
                  ],
                  id: "minecraft:furnace",
                  longs: [L; 1L, -2L]
                }""", NbtData.prettySnbt(tag));
        assertEquals("compound", tag.typeName());
        assertInstanceOf(NbtData.ShortTag.class, ((NbtData.CompoundTag) tag).entries().get("BurnTime"));
    }

    @Test
    void pathsUseTheDataCommandSyntax() {
        assertEquals("", NbtData.path(List.of()));
        assertEquals("Items[0].components", NbtData.path(List.of("Items", 0, "components")));
        assertEquals("minecraft:custom_data.\"odd key\"[2]",
                NbtData.path(List.of("minecraft:custom_data", "odd key", 2)));
        assertEquals("a.\"with.dot\".\"quote\\\"d\"", NbtData.path(List.of("a", "with.dot", "quote\"d")));
        assertEquals("[3]", NbtData.path(List.of(3)));
    }

    @Test
    void rejectsMalformedData() {
        assertThrows(IllegalArgumentException.class, () -> NbtData.read(new byte[]{3, 0, 0}));
        assertThrows(IllegalArgumentException.class, () -> NbtData.read(new byte[]{3, 0, 0, 0, 1, 9}));
        assertThrows(IllegalArgumentException.class, () -> NbtData.read(new byte[]{13}));
        assertThrows(IllegalArgumentException.class, () -> NbtData.read(new byte[]{11, -1, -1, -1, -1}));
        assertThrows(IllegalArgumentException.class, () -> NbtData.read(new byte[]{9, 0, 0, 0, 0, 1}));
    }

    @Test
    void rejectsNestingBeyondMinecraftsLimit() throws IOException {
        byte[] deep = tag(output -> {
            output.writeByte(9);
            for (int depth = 0; depth < NbtData.MAX_DEPTH + 1; depth++) {
                output.writeByte(9);
                output.writeInt(1);
            }
            output.writeByte(0);
            output.writeInt(0);
        });

        assertThrows(IllegalArgumentException.class, () -> NbtData.read(deep));
    }

    private interface Writer {
        void write(DataOutputStream output) throws IOException;
    }

    private static byte[] tag(Writer writer) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            writer.write(output);
        }
        return bytes.toByteArray();
    }
}
