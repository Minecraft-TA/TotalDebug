package com.github.minecraft_ta.totalDebugCompanion.ui.components;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExpressionCompletionSupportTest {
    @Test
    void findsTheIdentifierFragmentAroundTheCaret() {
        var range = ExpressionCompletionSupport.completionRange("level == true", 3);

        assertEquals(0, range.start());
        assertEquals(5, range.end());
        assertEquals("lev", range.prefix());
        assertFalse(range.memberAccess());
    }

    @Test
    void recognizesUnsupportedMemberCompletionWithoutGuessing() {
        var range = ExpressionCompletionSupport.completionRange("state.val", 8);

        assertEquals("va", range.prefix());
        assertTrue(range.memberAccess());
    }
}
