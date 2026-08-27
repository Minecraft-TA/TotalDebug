package com.github.minecraft_ta.totalDebugCompanion.debugger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebuggerCompletionRangeTest {
    @Test
    void preservesTheTokenAroundAMiddleCaret() {
        DebuggerCompletionRange range = DebuggerCompletionRange.around("target.ownSecret", 9);

        assertEquals(7, range.start());
        assertEquals("target.ownSecret".length(), range.end());
        assertEquals("ow", range.prefix());
        assertTrue(range.memberAccess());
        assertEquals(6, range.ownerEnd());
    }

    @Test
    void mapsMultilineAdapterPositionsToOffsets() {
        assertEquals(7, DebuggerCompletionRange.offsetOf("first\nsecond", 1, 1));
    }
}
