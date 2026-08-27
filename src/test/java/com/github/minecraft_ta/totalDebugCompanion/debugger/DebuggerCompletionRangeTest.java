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
        assertEquals(0, range.ownerStart());
        assertEquals(6, range.ownerEnd());
        assertEquals("target", range.ownerExpression("target.ownSecret"));
    }

    @Test
    void isolatesTheMemberOwnerInsideACompoundExpression() {
        String expression = "pos.y + pos.";

        DebuggerCompletionRange range = DebuggerCompletionRange.around(expression, expression.length());

        assertEquals(8, range.ownerStart());
        assertEquals(11, range.ownerEnd());
        assertEquals("pos", range.ownerExpression(expression));
    }

    @Test
    void mapsMultilineAdapterPositionsToOffsets() {
        assertEquals(7, DebuggerCompletionRange.offsetOf("first\nsecond", 1, 1));
    }
}
