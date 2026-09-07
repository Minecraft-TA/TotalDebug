package com.github.minecraft_ta.totalDebugCompanion.ui.components;

import com.github.minecraft_ta.totalDebugCompanion.debugger.DebuggerCompletionRange;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpressionCompletionSupportTest {
    @Test
    void findsTheIdentifierFragmentAroundTheCaret() {
        var range = DebuggerCompletionRange.around("level == true", 3);

        assertEquals(0, range.start());
        assertEquals(5, range.end());
        assertEquals("lev", range.prefix());
        assertFalse(range.memberAccess());
    }

    @Test
    void recognizesUnsupportedMemberCompletionWithoutGuessing() {
        var range = DebuggerCompletionRange.around("state.val", 8);

        assertEquals("va", range.prefix());
        assertTrue(range.memberAccess());
    }

    @Test
    void sharesTheSameRangeWhenTheCaretIsInsideAToken() {
        var range = DebuggerCompletionRange.around("target.field", 9);

        assertEquals(7, range.start());
        assertEquals(12, range.end());
        assertEquals("fi", range.prefix());
        assertTrue(range.memberAccess());
    }
}
