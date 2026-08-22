package com.github.minecraft_ta.totaldebug.bytecode;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ClassLoaderBytecodeSourceTest {
    @Test
    void readsTheTargetClassUsingAllSupportedNameForms() throws Exception {
        ClassLoaderBytecodeSource source = ClassLoaderBytecodeSource.forClass(Fixture.class);
        byte[] byBinaryName = source.findClassBytes(Fixture.class.getName());

        assertArrayEquals(byBinaryName, source.findClassBytes(Fixture.class.getName().replace('.', '/')));
        assertArrayEquals(byBinaryName, source.findClassBytes('/' + Fixture.class.getName().replace('.', '/') + ".class"));
        assertEquals(0xCA, Byte.toUnsignedInt(byBinaryName[0]));
        assertEquals(0xFE, Byte.toUnsignedInt(byBinaryName[1]));
        assertEquals(0xBA, Byte.toUnsignedInt(byBinaryName[2]));
        assertEquals(0xBE, Byte.toUnsignedInt(byBinaryName[3]));
        assertSame(Fixture.class.getClassLoader(), source.definingClassLoader());
        assertNotNull(source.findClassResource(Fixture.class.getName()));
    }

    @Test
    void returnsNullForAnUnknownClass() throws Exception {
        ClassLoaderBytecodeSource source = ClassLoaderBytecodeSource.forClass(Fixture.class);

        assertNull(source.findClassBytes("missing.DoesNotExist"));
    }

    private static final class Fixture {
    }
}
