package com.github.minecraft_ta.totaldebug.decompiler.naming;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JadLikeNameGeneratorTest {
    @Test
    void derivesStableNamesFromTypes() {
        JadLikeNameGenerator names = new JadLikeNameGenerator();

        assertEquals("properties", names.next("BlockBehaviour.Properties"));
        assertEquals("s", names.next("String"));
        assertEquals("astring", names.next("java.lang.String[]"));
        assertEquals("i", names.next("int"));
        assertEquals("j", names.next("long"));
    }

    @Test
    void avoidsNamesAlreadyPresentInTheMethod() {
        JadLikeNameGenerator names = new JadLikeNameGenerator();
        names.reserve(List.of("properties", "i"));

        assertEquals("properties1", names.next("Properties"));
        assertEquals("j", names.next("int"));
    }
}
