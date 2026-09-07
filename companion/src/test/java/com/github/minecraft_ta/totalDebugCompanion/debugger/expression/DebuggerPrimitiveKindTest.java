package com.github.minecraft_ta.totalDebugCompanion.debugger.expression;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebuggerPrimitiveKindTest {
    @Test
    void modelsJavaPrimitiveIdentityAndWidening() {
        assertEquals(0, DebuggerPrimitiveKind.BOOLEAN.wideningCostTo(DebuggerPrimitiveKind.BOOLEAN));
        assertEquals(-1, DebuggerPrimitiveKind.INT.wideningCostTo(DebuggerPrimitiveKind.BOOLEAN));
        assertEquals(1, DebuggerPrimitiveKind.INT.wideningCostTo(DebuggerPrimitiveKind.LONG));
        assertEquals(1, DebuggerPrimitiveKind.CHAR.wideningCostTo(DebuggerPrimitiveKind.INT));
        assertEquals(-1, DebuggerPrimitiveKind.CHAR.wideningCostTo(DebuggerPrimitiveKind.SHORT));
        assertEquals(-1, DebuggerPrimitiveKind.INT.wideningCostTo(DebuggerPrimitiveKind.CHAR));
    }

    @Test
    void distinguishesWrappersFromPrimitiveNames() {
        assertEquals(DebuggerPrimitiveKind.INT, DebuggerPrimitiveKind.fromTypeName("int"));
        assertEquals(DebuggerPrimitiveKind.INT, DebuggerPrimitiveKind.fromTypeName("java.lang.Integer"));
        assertTrue(DebuggerPrimitiveKind.fromTypeName("java.lang.Long")
                .wideningCostTo(DebuggerPrimitiveKind.INT) < 0);
    }
}
