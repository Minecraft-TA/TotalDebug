package com.github.minecraft_ta.totalDebugCompanion.bytecode.reference;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ReferenceQueryTest {
    @Test
    void rejectsAmbiguousOrMalformedQueries() {
        assertThrows(IllegalArgumentException.class, () -> ReferenceQuery.classReference("fixture/Target"));
        assertThrows(IllegalArgumentException.class, () -> ReferenceQuery.classReference("[Lfixture.Target;"));
        assertThrows(
                IllegalArgumentException.class,
                () -> ReferenceQuery.fieldReference("fixture.Target", "VALUE", "()V")
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> ReferenceQuery.methodReference("fixture.Target", "run", "I")
        );
    }
}
