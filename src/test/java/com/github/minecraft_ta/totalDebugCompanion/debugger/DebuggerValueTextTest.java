package com.github.minecraft_ta.totalDebugCompanion.debugger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DebuggerValueTextTest {

    @Test
    void shortensGenericRuntimeObjectIdentities() {
        assertEquals(
                "LegacyRandomSource",
                DebuggerValueText.visibleValue(
                        "net.minecraft.world.level.levelgen.LegacyRandomSource@45",
                        "net.minecraft.util.RandomSource"
                )
        );
        assertEquals("byte[25]", DebuggerValueText.visibleValue("byte[25]@17", "byte[]"));
    }
}
