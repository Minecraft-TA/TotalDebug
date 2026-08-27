package com.github.minecraft_ta.totalDebugCompanion.debugger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DebuggerCompletionProposalTest {
    @Test
    void retainsReplacementAndCaretMetadata() {
        DebuggerCompletionProposal proposal = new DebuggerCompletionProposal(
                "size()", "size()", DebuggerCompletionProposal.Kind.METHOD,
                "int", 8, 11, 5, 12
        );

        assertEquals(8, proposal.replacementStart());
        assertEquals(11, proposal.replacementEnd());
        assertEquals(5, proposal.caretOffset());
        assertEquals(12, proposal.rank());
    }

    @Test
    void rejectsAnImpossibleCaret() {
        assertThrows(IllegalArgumentException.class, () -> new DebuggerCompletionProposal(
                "field", "field", DebuggerCompletionProposal.Kind.FIELD,
                "String", 0, 0, 6, 0
        ));
    }
}
